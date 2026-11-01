#![cfg(any(target_os = "linux", target_os = "macos"))]

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::mpsc::{channel, Receiver};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::Duration;

use cero_http::http2::trama::{FIN_CABECERAS, FIN_FLUJO, HEADERS, RST_STREAM, SETTINGS};
use cero_http::http2::{Error, Trama};
use cero_http::{Respuesta, Router, Servidor};

#[repr(C)]
struct Tope {
    actual: u64,
    maximo: u64,
}

unsafe extern "C" {
    fn getrlimit(recurso: i32, tope: *mut Tope) -> i32;
    fn setrlimit(recurso: i32, tope: *const Tope) -> i32;
}

#[cfg(target_os = "linux")]
const PROCESOS: i32 = 6;
#[cfg(target_os = "macos")]
const PROCESOS: i32 = 7;

fn limitar(actual: u64, original: &Tope) -> bool {
    let tope = Tope { actual, maximo: original.maximo };
    unsafe { setrlimit(PROCESOS, &tope) == 0 }
}

fn agotar(soltar: &Arc<Mutex<Receiver<()>>>) -> (bool, Vec<JoinHandle<()>>) {
    let mut dormidos = Vec::new();
    for _ in 0..20_000 {
        let espera = Arc::clone(soltar);
        let hilo = std::thread::Builder::new().stack_size(64 << 10).spawn(move || {
            let _ = espera.lock().map(|r| r.recv());
        });
        match hilo {
            Ok(h) => dormidos.push(h),
            Err(_) => return (true, dormidos),
        }
    }
    (false, dormidos)
}

fn conectar(puerto: u16) -> TcpStream {
    let c = (0..100)
        .find_map(|_| {
            TcpStream::connect(("127.0.0.1", puerto)).ok().or_else(|| {
                std::thread::sleep(Duration::from_millis(50));
                None
            })
        })
        .expect("escucha");
    c.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
    c
}

fn codigo(t: &Trama) -> u32 {
    u32::from_be_bytes([t.carga[0], t.carga[1], t.carga[2], t.carga[3]])
}

#[test]
fn sin_hilos_se_rechaza_el_flujo_o_se_cierra_la_conexion_y_el_proceso_sigue() {
    let mut original = Tope { actual: 0, maximo: 0 };
    assert_eq!(unsafe { getrlimit(PROCESOS, &mut original) }, 0);
    let puerto = TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port();
    let router = Router::nuevo().ruta("GET", "/", "hola").expect("patrón");
    let servidor = Servidor::nuevo(router).accion("hola", |_| Respuesta::texto("hola")).direccion("127.0.0.1");
    std::thread::spawn(move || servidor.escuchar(puerto));

    let mut h2 = conectar(puerto);
    h2.write_all(b"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n").unwrap();
    Trama { tipo: SETTINGS, banderas: 0, flujo: 0, carga: Vec::new() }.escribir(&mut h2).unwrap();
    while Trama::leer(&mut h2, 1 << 24).expect("SETTINGS del servidor").tipo != SETTINGS {}

    let (despertar, soltar) = channel::<()>();
    let soltar = Arc::new(Mutex::new(soltar));
    limitar(1, &original);
    let (agotado, dormidos) = agotar(&soltar);
    if !agotado {
        limitar(original.actual, &original);
        drop(despertar);
        eprintln!("  · aquí no se pueden agotar los hilos; la prueba no dice nada");
        return;
    }

    let pedido = Trama { tipo: HEADERS, banderas: FIN_FLUJO | FIN_CABECERAS, flujo: 1, carga: vec![0x82, 0x86, 0x84] };
    pedido.escribir(&mut h2).unwrap();
    let mut corte = None;
    while let Ok(t) = Trama::leer(&mut h2, 1 << 24) {
        if t.tipo == RST_STREAM && t.flujo == 1 {
            corte = Some(codigo(&t));
            break;
        }
    }

    let mut otra = conectar(puerto);
    let _ = otra.write_all(b"GET / HTTP/1.1\r\nHost: a\r\n\r\n");
    let mut algo = [0u8; 64];
    let cerrada = matches!(otra.read(&mut algo), Ok(0) | Err(_));

    limitar(original.actual, &original);
    drop(despertar);
    for h in dormidos {
        let _ = h.join();
    }

    assert_eq!(corte, Some(Error::Rechazado as u32), "sin hilo para un flujo: RST_STREAM REFUSED_STREAM");
    assert!(cerrada, "sin hilo para una conexión: se cierra esa conexión");
    let mut despues = TcpStream::connect(("127.0.0.1", puerto)).expect("el servidor sigue escuchando");
    despues.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
    despues.write_all(b"GET / HTTP/1.1\r\nHost: a\r\nConnection: close\r\n\r\n").unwrap();
    let mut respuesta = Vec::new();
    let _ = despues.read_to_end(&mut respuesta);
    assert!(respuesta.starts_with(b"HTTP/1.1 200"), "y, con hilos otra vez, atiende");
}
