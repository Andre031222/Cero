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
use std::net::TcpStream;
use std::sync::{Arc, Mutex};

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
/// El cuerpo se parte por `SETTINGS_MAX_FRAME_SIZE`, que es lo que la otra punta dijo que aceptaba.
/// Mandar una trama más grande es un `FRAME_SIZE_ERROR` que se lleva la conexión: el tope que el
/// cliente anuncia manda sobre lo que nos convenga.
fn responder(salida: &Arc<Mutex<Salida>>, flujo: u32, r: Respuesta, solo_cabeceras: bool) {
    let mut cabeceras = vec![("content-length".to_string(), r.cuerpo.len().to_string())];
    if !r.tipo.is_empty() {
        cabeceras.push(("content-type".to_string(), r.tipo.clone()));
    }
    cabeceras.extend(r.extra.iter().cloned());
    let bloque = cabeceras_de_respuesta(r.estado, &cabeceras);

    let Ok(mut s) = salida.lock() else { return };
    let vacio = solo_cabeceras || r.cuerpo.is_empty();
    // El bloque de cabeceras cabe siempre: la respuesta la escribe este servidor, no el cliente, y
    // si algún día no cupiera habría que repartirlo en CONTINUATION igual que se lee.
    let banderas = FIN_CABECERAS | if vacio { FIN_FLUJO } else { 0 };
    if s.trama(&Trama { tipo: HEADERS, banderas, flujo, carga: bloque }).is_err() || vacio {
        return;
    }
    let max = s.max_trama;
    let trozos: Vec<&[u8]> = r.cuerpo.chunks(max).collect();
    let ultimo = trozos.len() - 1;
    for (i, trozo) in trozos.iter().enumerate() {
        let banderas = if i == ultimo { FIN_FLUJO } else { 0 };
        if s.trama(&Trama { tipo: DATA, banderas, flujo, carga: trozo.to_vec() }).is_err() {
            return;
        }
    }
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
    let salida = Arc::new(Mutex::new(Salida { socket, max_trama: ajustes.max_trama as usize }));
    let mut sesion = Sesion::nueva(ajustes);

    // `H2-027`: los nuestros, primero. Antes de leer una sola trama del cliente, porque hasta que
    // los tenga está obligado a suponer los valores del RFC y no los nuestros.
    salida.lock().unwrap().trama(&ajustes.trama())?;

    let mut en_curso: HashMap<u32, EnCurso> = HashMap::new();
    let mut hilos: Vec<std::thread::JoinHandle<()>> = Vec::new();

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

        let (acciones, _cortado) = match sesion.recibir(trama) {
            Ok(x) => x,
            Err(fallo) => {
                let _ = salida.lock().unwrap().trama(&Trama::goaway(0, fallo.codigo));
                break;
            }
        };

        for accion in acciones {
            match accion {
                Accion::Escribir(t) => {
                    // Un ajuste nuevo del cliente cambia el tamaño de lo que se le puede mandar.
                    if t.tipo == SETTINGS && t.banderas & RECONOCE != 0 {
                        salida.lock().unwrap().max_trama = sesion.ajustes.max_trama as usize;
                    }
                    if salida.lock().unwrap().trama(&t).is_err() {
                        return Ok(());
                    }
                }
                Accion::Anulado(id) => {
                    en_curso.remove(&id);
                }
                Accion::Peticion { flujo, cabeceras, fin } => {
                    en_curso.insert(flujo, EnCurso { cabeceras, cuerpo: Vec::new() });
                    if fin {
                        lanzar(&mut hilos, &salida, &atender, &mut sesion, &mut en_curso, flujo);
                    }
                }
                Accion::Cuerpo { flujo, datos, fin } => {
                    if let Some(c) = en_curso.get_mut(&flujo) {
                        c.cuerpo.extend_from_slice(&datos);
                    }
                    if fin {
                        lanzar(&mut hilos, &salida, &atender, &mut sesion, &mut en_curso, flujo);
                    }
                }
            }
        }
    }

    // No se dejan hilos escribiendo sobre un socket que este hilo va a cerrar.
    for h in hilos {
        let _ = h.join();
    }
    Ok(())
}

fn lanzar<F>(
    hilos: &mut Vec<std::thread::JoinHandle<()>>,
    salida: &Arc<Mutex<Salida>>,
    atender: &Arc<F>,
    sesion: &mut Sesion,
    en_curso: &mut HashMap<u32, EnCurso>,
    flujo: u32,
) where
    F: Fn(Peticion) -> Respuesta + Send + Sync + 'static,
{
    let Some(EnCurso { cabeceras, cuerpo }) = en_curso.remove(&flujo) else { return };
    let peticion = armar(cabeceras, cuerpo);
    let solo_cabeceras = peticion.metodo == "HEAD";
    sesion.respondido(flujo);

    let salida = Arc::clone(salida);
    let atender = Arc::clone(atender);
    hilos.push(std::thread::spawn(move || {
        let r = atender(peticion);
        responder(&salida, flujo, r, solo_cabeceras);
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
