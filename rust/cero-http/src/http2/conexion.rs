//! El conductor de una conexión HTTP/2: lo que hay entre el socket y `Sesion`.
//!
//! La división es a propósito. `Sesion` no toca el socket y por eso se puede probar dando tramas y
//! mirando lo que pide hacer; aquí está lo que solo se puede comprobar con un socket de verdad —los
//! hilos, el candado de salida y el orden de escritura—.
//!
//! **Un hilo del sistema por flujo, no por conexión.** `H2-029` pide que los flujos se atiendan en
//! paralelo, y atenderlos en fila sobre el hilo que lee convertiría una petición lenta en un atasco
//! de las demás: sería HTTP/1.1 con más ceremonia. Como en el resto del proyecto, el modelo es hilo
//! del sistema porque `std` no ofrece otra cosa sin dependencias.

use std::collections::HashMap;
use std::io::{Read, Write};
use std::net::{Shutdown, TcpStream};
use std::sync::mpsc::{channel, Sender};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use super::credito::Credito;
use super::flujo::{cabeceras_de_respuesta, Accion, Sesion};
use super::trama::*;
use crate::contexto::Respuesta;
use crate::peticion::Peticion;

/// La salida, compartida por todos los hilos de flujo.
///
/// Un candado y no un canal: una trama tiene que salir entera o no salir. Dos escrituras
/// entrelazadas no dan dos tramas mal ordenadas, dan una trama corrupta y una conexión perdida.
struct Salida {
    socket: TcpStream,
    max_trama: usize,
}

impl Salida {
    fn trama(&mut self, t: &Trama) -> std::io::Result<()> {
        t.escribir(&mut self.socket)?;
        self.socket.flush()
    }
}

/// Escribe una respuesta entera sobre un flujo: HEADERS, el cuerpo en trozos y el fin.
///
/// El cuerpo se parte dos veces. Por `SETTINGS_MAX_FRAME_SIZE`, que es lo que la otra punta dijo
/// que aceptaba —una trama más grande es un `FRAME_SIZE_ERROR` que se lleva la conexión—, y por el
/// crédito que tenga el flujo en ese momento, que es lo que ha dicho que le cabe. Lo segundo puede
/// dejar este hilo esperando, y por eso el candado de salida se coge y se suelta por trama: quedarse
/// con él mientras se espera crédito pararía a los demás flujos, que es lo contrario de HTTP/2.
fn responder(
    salida: &Arc<Mutex<Salida>>,
    credito: &Credito,
    flujo: u32,
    r: Respuesta,
    solo_cabeceras: bool,
) {
    let mut cabeceras = vec![("content-length".to_string(), r.cuerpo.len().to_string())];
    if !r.tipo.is_empty() {
        cabeceras.push(("content-type".to_string(), r.tipo.clone()));
    }
    cabeceras.extend(r.extra.iter().cloned());
    let bloque = cabeceras_de_respuesta(r.estado, &cabeceras);

    let vacio = solo_cabeceras || r.cuerpo.is_empty();
    // El bloque de cabeceras cabe siempre: la respuesta la escribe este servidor, no el cliente, y
    // si algún día no cupiera habría que repartirlo en CONTINUATION igual que se lee. Las cabeceras
    // no gastan crédito: el control de flujo solo cuenta DATA (§6.9).
    let banderas = FIN_CABECERAS | if vacio { FIN_FLUJO } else { 0 };
    if !escribir(salida, &Trama { tipo: HEADERS, banderas, flujo, carga: bloque }) || vacio {
        return;
    }

    let mut resto = &r.cuerpo[..];
    while !resto.is_empty() {
        let max = { salida.lock().map(|s| s.max_trama).unwrap_or(0) };
        let Some(cabe) = credito.reservar(flujo, resto.len().min(max)) else { return };
        let (trozo, queda) = resto.split_at(cabe);
        let banderas = if queda.is_empty() { FIN_FLUJO } else { 0 };
        if !escribir(salida, &Trama { tipo: DATA, banderas, flujo, carga: trozo.to_vec() }) {
            return;
        }
        resto = queda;
    }
}

fn escribir(salida: &Arc<Mutex<Salida>>, t: &Trama) -> bool {
    salida.lock().map(|mut s| s.trama(t).is_ok()).unwrap_or(false)
}

/// Lo que se va juntando de un flujo hasta que la petición está completa.
struct EnCurso {
    cabeceras: Vec<(String, String)>,
    cuerpo: Vec<u8>,
}

/// Atiende una conexión que ya dijo el preámbulo. Vuelve cuando el socket se cierra o cuando algo
/// se lleva la conexión por delante, y en ese caso deja dicho el GOAWAY antes de irse.
///
/// `atender` corre en un hilo por flujo, así que tiene que ser compartible: es el pipeline del
/// servidor, que ya lo es porque no guarda estado por petición.
pub fn servir<F>(socket: TcpStream, ajustes: Ajustes, atender: Arc<F>) -> std::io::Result<()>
where
    F: Fn(Peticion) -> Respuesta + Send + Sync + 'static,
{
    let mut lectura = socket.try_clone()?;
    let par = Ajustes::del_rfc();
    let salida = Arc::new(Mutex::new(Salida { socket, max_trama: par.max_trama as usize }));
    let credito = Arc::new(Credito::nuevo(par.ventana_inicial));
    let mut sesion = Sesion::nueva(ajustes);

    // `H2-027`: los nuestros, primero. Antes de leer una sola trama del cliente, porque hasta que
    // los tenga está obligado a suponer los valores del RFC y no los nuestros.
    salida.lock().unwrap().trama(&ajustes.trama())?;

    let mut en_curso: HashMap<u32, EnCurso> = HashMap::new();
    let mut hilos: Vec<std::thread::JoinHandle<()>> = Vec::new();
    // `H2-046`: un flujo deja de contar cuando se le **responde**, no cuando se le despacha. Lo
    // sabe el hilo que responde y lo necesita el que lee, y esa es toda la conversación que tienen.
    let (terminados, recoger) = channel::<u32>();

    loop {
        let trama = match Trama::leer(&mut lectura, sesion.ajustes.max_trama) {
            Ok(t) => t,
            // Se acabó el socket, o llegó algo que ni se puede enmarcar. Lo segundo merece GOAWAY.
            Err(fallo) => {
                if fallo.codigo != Error::Ninguno {
                    let _ = salida.lock().unwrap().trama(&Trama::goaway(0, fallo.codigo));
                }
                break;
            }
        };

        for id in recoger.try_iter() {
            sesion.respondido(id);
        }

        let (acciones, _cortado) = match sesion.recibir(trama) {
            Ok(x) => x,
            Err(fallo) => {
                let _ = salida.lock().unwrap().trama(&Trama::goaway(0, fallo.codigo));
                break;
            }
        };
        let mut seguir = true;

        for accion in acciones {
            match accion {
                Accion::Escribir(t) => {
                    // Un ajuste nuevo del cliente cambia el tamaño de lo que se le puede mandar.
                    if t.tipo == SETTINGS && t.banderas & RECONOCE != 0 {
                        salida.lock().unwrap().max_trama = sesion.par.max_trama as usize;
                    }
                    if salida.lock().unwrap().trama(&t).is_err() {
                        return Ok(());
                    }
                }
                Accion::Anulado(id) => {
                    en_curso.remove(&id);
                    credito.anular(id);
                }
                // §6.9.1: el que se pasa del tope es quien manda el incremento, así que el error es
                // suyo. Sobre un flujo se lleva el flujo; sobre la conexión, la conexión.
                Accion::Credito { flujo, cuanto } => {
                    if !credito.ampliar(flujo, cuanto) {
                        seguir = desbordada(&salida, flujo);
                    }
                }
                Accion::Reajustar(delta) => {
                    if !credito.reajustar(delta) {
                        seguir = desbordada(&salida, 0);
                    }
                }
                Accion::Peticion { flujo, cabeceras, fin } => {
                    credito.abrir(flujo);
                    en_curso.insert(flujo, EnCurso { cabeceras, cuerpo: Vec::new() });
                    if fin {
                        lanzar(&mut hilos, &salida, &credito, &atender, &terminados, &mut en_curso, flujo);
                    }
                }
                Accion::Cuerpo { flujo, datos, fin } => {
                    if let Some(c) = en_curso.get_mut(&flujo) {
                        c.cuerpo.extend_from_slice(&datos);
                    }
                    if fin {
                        lanzar(&mut hilos, &salida, &credito, &atender, &terminados, &mut en_curso, flujo);
                    }
                }
            }
        }
        if !seguir {
            break;
        }
    }

    // Nadie se queda esperando un crédito que ya no va a llegar: si no, el `join` de abajo espera a
    // un hilo que espera a este.
    credito.cerrar();
    // No se dejan hilos escribiendo sobre un socket que este hilo va a cerrar.
    for h in hilos {
        let _ = h.join();
    }
    despedirse(&mut lectura);
    Ok(())
}

/// `H2-048`: cierra de forma que lo último que se escribió llegue a leerse.
///
/// Un `close` con octetos sin leer en el búfer de recepción no manda un FIN, manda un RST, y un RST
/// borra de la otra punta lo que todavía no había leído. El GOAWAY que explica el fallo se escribía
/// bien y se perdía siempre: el cliente veía «connection reset by peer» y no el motivo. Es un fallo
/// de conformidad que no se ve en el código que lo causa, y ninguna prueba propia lo veía porque
/// todas leen la respuesta antes de cerrar.
///
/// La cura es la del RFC 9113 §9.1: media conexión primero, vaciar lo que quede después.
fn despedirse(socket: &mut TcpStream) {
    if socket.shutdown(Shutdown::Write).is_err() {
        return;
    }
    let _ = socket.set_read_timeout(Some(Duration::from_millis(250)));
    let mut tirar = [0u8; 4096];
    while let Ok(leidos) = socket.read(&mut tirar) {
        if leidos == 0 {
            return;
        }
    }
}

/// Una ventana que se pasa de 2^31-1. Devuelve si la conexión sigue viva después.
fn desbordada(salida: &Arc<Mutex<Salida>>, flujo: u32) -> bool {
    if flujo == 0 {
        let _ = escribir(salida, &Trama::goaway(0, Error::ControlDeFlujo));
        return false;
    }
    escribir(salida, &Trama::rst(flujo, Error::ControlDeFlujo))
}

fn lanzar<F>(
    hilos: &mut Vec<std::thread::JoinHandle<()>>,
    salida: &Arc<Mutex<Salida>>,
    credito: &Arc<Credito>,
    atender: &Arc<F>,
    terminados: &Sender<u32>,
    en_curso: &mut HashMap<u32, EnCurso>,
    flujo: u32,
) where
    F: Fn(Peticion) -> Respuesta + Send + Sync + 'static,
{
    let Some(EnCurso { cabeceras, cuerpo }) = en_curso.remove(&flujo) else { return };
    let peticion = armar(cabeceras, cuerpo);
    let solo_cabeceras = peticion.metodo == "HEAD";

    let salida = Arc::clone(salida);
    let credito = Arc::clone(credito);
    let atender = Arc::clone(atender);
    let terminados = terminados.clone();
    hilos.push(std::thread::spawn(move || {
        let r = atender(peticion);
        responder(&salida, &credito, flujo, r, solo_cabeceras);
        let _ = terminados.send(flujo);
    }));
    // Los hilos ya terminados no se acumulan: una conexión larga con miles de peticiones dejaría
    // miles de asas vivas, que es una fuga aunque cada una sea pequeña.
    hilos.retain(|h| !h.is_finished());
}

/// Convierte los pseudo-campos en la petición que el resto del framework ya sabe atender.
///
/// El `:authority` pasa a `host`: es el mismo dato con otro nombre, y dejar que las capas de arriba
/// pregunten por uno u otro según el protocolo es la clase de API que este framework evita.
fn armar(cabeceras: Vec<(String, String)>, cuerpo: Vec<u8>) -> Peticion {
    let mut metodo = String::new();
    let mut destino = String::from("/");
    let mut mapa = HashMap::new();
    for (nombre, valor) in cabeceras {
        match nombre.as_str() {
            ":method" => metodo = valor,
            ":path" => destino = valor,
            ":authority" => {
                mapa.insert("host".to_string(), valor);
            }
            ":scheme" => {}
            _ => {
                // Varios valores del mismo campo se juntan con coma, igual que en HTTP/1.1.
                mapa.entry(nombre)
                    .and_modify(|v: &mut String| {
                        v.push_str(", ");
                        v.push_str(&valor);
                    })
                    .or_insert(valor);
            }
        }
    }
    Peticion { metodo, destino, version: "HTTP/2".to_string(), cabeceras: mapa, cuerpo }
}

/// Mira si lo que empieza la conexión es el preámbulo, **sin consumirlo si no lo es**.
///
/// `H2-001` y la puerta de entrada por conocimiento previo. En un puerto compartido con HTTP/1.1
/// esto es lo único que las distingue, y equivocarse en un sentido deja a un navegador sin respuesta
/// y en el otro contesta binario a quien habla texto.
pub fn asomar_preambulo(socket: &mut TcpStream) -> std::io::Result<Option<Vec<u8>>> {
    let mut visto = Vec::new();
    let mut uno = [0u8; 1];
    while visto.len() < PREAMBULO.len() {
        let leidos = socket.read(&mut uno)?;
        if leidos == 0 {
            return Ok(Some(visto));
        }
        visto.push(uno[0]);
        if visto[visto.len() - 1] != PREAMBULO[visto.len() - 1] {
            // No es h2: se devuelve lo leído para que lo atienda quien habla HTTP/1.1.
            return Ok(Some(visto));
        }
    }
    Ok(None)
}
