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

struct EnCurso {
    cabeceras: Vec<(String, String)>,
    cuerpo: Vec<u8>,
}

pub fn servir<F>(socket: TcpStream, ajustes: Ajustes, atender: Arc<F>) -> std::io::Result<()>
where
    F: Fn(Peticion) -> Respuesta + Send + Sync + 'static,
{
    let mut lectura = socket.try_clone()?;
    let par = Ajustes::del_rfc();
    let salida = Arc::new(Mutex::new(Salida { socket, max_trama: par.max_trama as usize }));
    let credito = Arc::new(Credito::nuevo(par.ventana_inicial));
    let mut sesion = Sesion::nueva(ajustes);

    salida.lock().unwrap().trama(&ajustes.trama())?;

    let mut en_curso: HashMap<u32, EnCurso> = HashMap::new();
    let mut hilos: Vec<std::thread::JoinHandle<()>> = Vec::new();
    let (terminados, recoger) = channel::<u32>();

    loop {
        let trama = match Trama::leer(&mut lectura, sesion.ajustes.max_trama) {
            Ok(t) => t,
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

    credito.cerrar();
    for h in hilos {
        let _ = h.join();
    }
    despedirse(&mut lectura);
    Ok(())
}

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
    hilos.retain(|h| !h.is_finished());
}

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
            return Ok(Some(visto));
        }
    }
    Ok(None)
}
