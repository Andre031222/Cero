use std::collections::HashMap;
use std::io::{ErrorKind, Read};
use std::net::{Shutdown, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::mpsc::{channel, Sender};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use super::credito::Credito;
use super::flujo::{cabeceras_de_respuesta, Accion, Sesion};
use super::trama::*;
use crate::contexto::Respuesta;
use crate::peticion::Peticion;
use crate::socket::{mandar_todo, Limites, Plazos};

struct Salida {
    socket: TcpStream,
    max_trama: usize,
    plazos: Plazos,
    rota: bool,
}

impl Salida {
    fn trama(&mut self, t: &Trama) -> std::io::Result<()> {
        if self.rota {
            return Err(ErrorKind::BrokenPipe.into());
        }
        let mut octetos = Vec::with_capacity(9 + t.carga.len());
        t.escribir(&mut octetos)?;
        let escrito = mandar_todo(&mut self.socket, &octetos, &self.plazos);
        if escrito.is_err() {
            self.rota = true;
            let _ = self.socket.shutdown(Shutdown::Both);
        }
        escrito
    }
}

struct Comun {
    salida: Mutex<Salida>,
    credito: Credito,
    plazos: Plazos,
    limites: Limites,
    por_mandar: Mutex<usize>,
    ultimo: AtomicU32,
    calma: AtomicBool,
}

impl Comun {
    fn escribir(&self, t: &Trama) -> bool {
        self.salida.lock().map(|mut s| s.trama(t).is_ok()).unwrap_or(false)
    }

    fn max_trama(&self) -> usize {
        self.salida.lock().map(|s| s.max_trama).unwrap_or(0)
    }

    fn cargar(&self, octetos: usize) -> bool {
        let mut por_mandar = self.por_mandar.lock().unwrap();
        if *por_mandar > 0 && *por_mandar + octetos > self.limites.en_vuelo {
            return false;
        }
        *por_mandar += octetos;
        true
    }

    fn descargar(&self, octetos: usize) {
        *self.por_mandar.lock().unwrap() -= octetos;
    }

    fn calmar(&self) {
        self.calma.store(true, Ordering::SeqCst);
        if let Ok(mut s) = self.salida.lock() {
            let goaway = Trama::goaway(self.ultimo.load(Ordering::SeqCst), Error::Calma);
            if s.trama(&goaway).is_ok() {
                let _ = s.socket.shutdown(Shutdown::Write);
            }
            s.rota = true;
        }
        self.credito.cerrar();
    }
}

struct PorMandar<'c> {
    comun: &'c Comun,
    octetos: usize,
}

impl PorMandar<'_> {
    fn mandados(&mut self, octetos: usize) {
        self.comun.descargar(octetos);
        self.octetos -= octetos;
    }
}

impl Drop for PorMandar<'_> {
    fn drop(&mut self) {
        self.comun.descargar(self.octetos);
    }
}

fn responder(comun: &Comun, flujo: u32, r: Respuesta, solo_cabeceras: bool) {
    let mut cabeceras = vec![("content-length".to_string(), r.cuerpo.len().to_string())];
    if !r.tipo.is_empty() {
        cabeceras.push(("content-type".to_string(), r.tipo.clone()));
    }
    cabeceras.extend(r.extra.iter().cloned());
    let bloque = cabeceras_de_respuesta(r.estado, &cabeceras);

    let vacio = solo_cabeceras || r.cuerpo.is_empty();
    let banderas = FIN_CABECERAS | if vacio { FIN_FLUJO } else { 0 };
    if !comun.escribir(&Trama { tipo: HEADERS, banderas, flujo, carga: bloque }) || vacio {
        return;
    }

    let mut resto = &r.cuerpo[..];
    if !comun.cargar(resto.len()) {
        comun.calmar();
        return;
    }
    let mut pendiente = PorMandar { comun, octetos: resto.len() };
    while !resto.is_empty() {
        let mut trama = resto.len().min(comun.max_trama());
        let hasta = Instant::now() + comun.plazos.escritura;
        while trama > 0 {
            let Some(cabe) = comun.credito.reservar(flujo, trama, hasta) else {
                if Instant::now() >= hasta {
                    comun.escribir(&Trama::rst(flujo, Error::Anulado));
                }
                return;
            };
            let (trozo, queda) = resto.split_at(cabe);
            let banderas = if queda.is_empty() { FIN_FLUJO } else { 0 };
            if !comun.escribir(&Trama { tipo: DATA, banderas, flujo, carga: trozo.to_vec() }) {
                return;
            }
            resto = queda;
            trama -= cabe;
            pendiente.mandados(cabe);
        }
    }
}

struct EnCurso {
    cabeceras: Vec<(String, String)>,
    cuerpo: Vec<u8>,
}

fn hay_datos(socket: &mut TcpStream, cuanto: Duration) -> bool {
    if socket.set_read_timeout(Some(cuanto.max(Duration::from_millis(1)))).is_err() {
        return false;
    }
    !matches!(socket.peek(&mut [0u8]), Err(e) if matches!(e.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut))
}

pub fn servir<F>(
    socket: TcpStream,
    ajustes: Ajustes,
    atender: Arc<F>,
    plazos: Plazos,
    limites: Limites,
) -> std::io::Result<()>
where
    F: Fn(Peticion) -> Respuesta + Send + Sync + 'static,
{
    let mut lectura = socket.try_clone()?;
    let par = Ajustes::del_rfc();
    let comun = Arc::new(Comun {
        salida: Mutex::new(Salida { socket, max_trama: par.max_trama as usize, plazos, rota: false }),
        credito: Credito::nuevo(par.ventana_inicial),
        plazos,
        limites,
        por_mandar: Mutex::new(0),
        ultimo: AtomicU32::new(0),
        calma: AtomicBool::new(false),
    });
    let mut sesion = Sesion::nueva(ajustes);

    comun.salida.lock().unwrap().trama(&ajustes.trama())?;

    let mut en_curso: HashMap<u32, EnCurso> = HashMap::new();
    let mut hilos: Vec<JoinHandle<()>> = Vec::new();
    let (terminados, recoger) = channel::<u32>();
    let mut ociosa_desde = Instant::now();

    while !comun.calma.load(Ordering::SeqCst) {
        let queda = (ociosa_desde + plazos.inactividad).saturating_duration_since(Instant::now());
        if !hay_datos(&mut lectura, queda) {
            if hilos.iter().any(|h| !h.is_finished()) {
                ociosa_desde = Instant::now();
                continue;
            }
            comun.escribir(&Trama::goaway(sesion.ultimo(), Error::Ninguno));
            break;
        }
        lectura.set_read_timeout(Some(plazos.inactividad))?;
        let trama = match Trama::leer(&mut lectura, sesion.ajustes.max_trama) {
            Ok(t) => t,
            Err(fallo) => {
                if fallo.codigo != Error::Ninguno {
                    comun.escribir(&Trama::goaway(0, fallo.codigo));
                }
                break;
            }
        };
        if comun.calma.load(Ordering::SeqCst) {
            break;
        }
        if matches!(trama.tipo, HEADERS | CONTINUATION | DATA) {
            ociosa_desde = Instant::now();
        }

        for id in recoger.try_iter() {
            sesion.respondido(id);
        }

        let recibido = sesion.recibir(trama);
        comun.ultimo.store(sesion.ultimo(), Ordering::SeqCst);
        let (acciones, _cortado) = match recibido {
            Ok(x) => x,
            Err(fallo) => {
                comun.escribir(&Trama::goaway(0, fallo.codigo));
                break;
            }
        };
        let mut seguir = true;

        for accion in acciones {
            match accion {
                Accion::Escribir(t) => {
                    if t.tipo == SETTINGS && t.banderas & RECONOCE != 0 {
                        comun.salida.lock().unwrap().max_trama = sesion.par.max_trama as usize;
                    }
                    if !comun.escribir(&t) {
                        return Ok(());
                    }
                }
                Accion::Anulado(id) => {
                    en_curso.remove(&id);
                    comun.credito.anular(id);
                }
                Accion::Credito { flujo, cuanto } => {
                    if !comun.credito.ampliar(flujo, cuanto) {
                        seguir = desbordada(&comun, flujo);
                    }
                }
                Accion::Reajustar(delta) => {
                    if !comun.credito.reajustar(delta) {
                        seguir = desbordada(&comun, 0);
                    }
                }
                Accion::Peticion { flujo, cabeceras, fin } => {
                    if anuncia_de_mas(&cabeceras, limites.cuerpo) {
                        cuerpo_de_mas(&comun, &mut sesion, flujo);
                        continue;
                    }
                    comun.credito.abrir(flujo);
                    en_curso.insert(flujo, EnCurso { cabeceras, cuerpo: Vec::new() });
                    if fin {
                        lanzar(&mut hilos, &comun, &atender, &terminados, &mut en_curso, flujo);
                    }
                }
                Accion::Cuerpo { flujo, datos, fin } => {
                    let Some(c) = en_curso.get_mut(&flujo) else { continue };
                    if datos.len() > limites.cuerpo - c.cuerpo.len() {
                        en_curso.remove(&flujo);
                        cuerpo_de_mas(&comun, &mut sesion, flujo);
                        continue;
                    }
                    c.cuerpo.extend_from_slice(&datos);
                    if fin {
                        lanzar(&mut hilos, &comun, &atender, &terminados, &mut en_curso, flujo);
                    }
                }
            }
        }
        if !seguir {
            break;
        }
    }

    comun.credito.cerrar();
    for h in hilos {
        let _ = h.join();
    }
    despedirse(&mut lectura);
    Ok(())
}

fn anuncia_de_mas(cabeceras: &[(String, String)], tope: usize) -> bool {
    cabeceras
        .iter()
        .find(|(n, _)| n == "content-length")
        .and_then(|(_, v)| v.parse::<u64>().ok())
        .is_some_and(|largo| largo > tope as u64)
}

fn cuerpo_de_mas(comun: &Comun, sesion: &mut Sesion, flujo: u32) {
    let bloque = cabeceras_de_respuesta(413, &[("content-length".to_string(), "0".to_string())]);
    comun.escribir(&Trama { tipo: HEADERS, banderas: FIN_CABECERAS | FIN_FLUJO, flujo, carga: bloque });
    comun.escribir(&Trama::rst(flujo, Error::Ninguno));
    comun.credito.anular(flujo);
    sesion.desechar(flujo);
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

fn desbordada(comun: &Comun, flujo: u32) -> bool {
    if flujo == 0 {
        let _ = comun.escribir(&Trama::goaway(0, Error::ControlDeFlujo));
        return false;
    }
    comun.escribir(&Trama::rst(flujo, Error::ControlDeFlujo))
}

fn lanzar<F>(
    hilos: &mut Vec<JoinHandle<()>>,
    comun: &Arc<Comun>,
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

    let comun = Arc::clone(comun);
    let atender = Arc::clone(atender);
    let terminados = terminados.clone();
    hilos.push(std::thread::spawn(move || {
        let r = atender(peticion);
        responder(&comun, flujo, r, solo_cabeceras);
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
