use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use cero_http::http2::trama::{DATA, FIN_CABECERAS, FIN_FLUJO, GOAWAY, HEADERS, PING, RST_STREAM, SETTINGS};
use cero_http::http2::{self, Ajustes, Error, Trama};
use cero_http::{Peticion, Plazos, Respuesta, Router, Servidor};

const GET_RAIZ: [u8; 3] = [0x82, 0x86, 0x84];

fn ms(n: u64) -> Duration {
    Duration::from_millis(n)
}

fn plazos(escritura: u64, inactividad: u64) -> Plazos {
    Plazos { sin_avance: ms(30_000), escritura: ms(escritura), inactividad: ms(inactividad) }
}

fn h2(plazos: Plazos, cuerpo: usize, ventana_inicial: Option<u32>) -> (TcpStream, JoinHandle<()>) {
    let oyente = TcpListener::bind("127.0.0.1:0").expect("puerto");
    let puerto = oyente.local_addr().unwrap().port();
    let hilo = std::thread::spawn(move || {
        let (socket, _) = oyente.accept().expect("conexión");
        let atender = Arc::new(move |_: Peticion| Respuesta::texto(&"x".repeat(cuerpo)));
        let _ = http2::conexion::servir(socket, Ajustes::default(), atender, plazos);
    });
    let mut cliente = TcpStream::connect(("127.0.0.1", puerto)).expect("conectar");
    cliente.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
    let ajustes = ventana_inicial.map(|v| [&[0u8, 4][..], &v.to_be_bytes()[..]].concat()).unwrap_or_default();
    Trama { tipo: SETTINGS, banderas: 0, flujo: 0, carga: ajustes }.escribir(&mut cliente).unwrap();
    (cliente, hilo)
}

fn pedir(cliente: &mut TcpStream, flujo: u32) {
    let t = Trama { tipo: HEADERS, banderas: FIN_FLUJO | FIN_CABECERAS, flujo, carga: GET_RAIZ.to_vec() };
    t.escribir(cliente).unwrap();
}

fn codigo(t: &Trama) -> u32 {
    let i = if t.tipo == GOAWAY { 4 } else { 0 };
    u32::from_be_bytes([t.carga[i], t.carga[i + 1], t.carga[i + 2], t.carga[i + 3]])
}

fn hasta(cliente: &mut TcpStream, cumple: impl Fn(&Trama) -> bool) -> (Option<Trama>, usize) {
    let mut datos = 0;
    while let Ok(t) = Trama::leer(cliente, 1 << 24) {
        if t.tipo == DATA {
            datos += t.carga.len();
        }
        if cumple(&t) {
            return (Some(t), datos);
        }
    }
    (None, datos)
}

fn latir(cliente: &TcpStream, cada: Duration, trama: Trama) -> (Arc<AtomicBool>, JoinHandle<()>) {
    let parar = Arc::new(AtomicBool::new(false));
    let suyo = Arc::clone(&parar);
    let mut socket = cliente.try_clone().unwrap();
    let hilo = std::thread::spawn(move || {
        while !suyo.load(Ordering::SeqCst) && trama.escribir(&mut socket).is_ok() {
            std::thread::sleep(cada);
        }
    });
    (parar, hilo)
}

#[test]
fn h2_052_un_cliente_callado_con_la_ventana_a_cero_suelta_sus_flujos() {
    let (mut c, hilo) = h2(plazos(300, 300), 4, Some(0));
    pedir(&mut c, 1);
    pedir(&mut c, 3);
    let empezo = Instant::now();
    let cortados = std::cell::Cell::new(0);
    let (adios, _) = hasta(&mut c, |t| {
        if t.tipo == RST_STREAM && codigo(t) == Error::Anulado as u32 {
            cortados.set(cortados.get() + 1);
        }
        t.tipo == GOAWAY
    });
    assert_eq!(cortados.get(), 2, "H2-052: los dos flujos sin crédito se cortan con CANCEL");
    assert_eq!(adios.map(|t| codigo(&t)), Some(0), "H2-053: y después el silencio se despide");
    assert!(empezo.elapsed() < ms(2_000), "dentro de los dos plazos");
    drop(c);
    hilo.join().unwrap();
}

#[test]
fn h2_052_el_goteo_de_credito_no_reinicia_el_plazo() {
    let (mut c, hilo) = h2(plazos(500, 30_000), 100, Some(0));
    pedir(&mut c, 1);
    let (parar, goteo) = latir(&c, ms(50), Trama::ventana(1, 1));
    let (corte, datos) = hasta(&mut c, |t| t.tipo == RST_STREAM && t.flujo == 1);
    parar.store(true, Ordering::SeqCst);
    goteo.join().unwrap();
    assert_eq!(corte.map(|t| codigo(&t)), Some(Error::Anulado as u32),
               "H2-052: un octeto cada 50 ms no salva una trama en 500 ms");
    assert!(datos > 0 && datos < 100, "H2-052: salió lo que cabía, no la respuesta entera");
    drop(c);
    hilo.join().unwrap();
}

#[test]
fn h2_053_los_ping_no_reinician_la_inactividad() {
    let (mut c, hilo) = h2(plazos(30_000, 300), 4, None);
    let (parar, latido) = latir(&c, ms(100), Trama { tipo: PING, banderas: 0, flujo: 0, carga: b"12345678".to_vec() });
    let empezo = Instant::now();
    let (adios, _) = hasta(&mut c, |t| t.tipo == GOAWAY);
    parar.store(true, Ordering::SeqCst);
    latido.join().unwrap();
    assert_eq!(adios.map(|t| codigo(&t)), Some(0), "H2-053: PING no es trabajo, y la conexión se despide");
    assert!(empezo.elapsed() < ms(2_000), "H2-053: dentro del plazo de inactividad");
    drop(c);
    hilo.join().unwrap();
}

#[test]
fn h2_054_las_respuestas_en_vuelo_tienen_presupuesto() {
    let (mut c, hilo) = h2(plazos(30_000, 30_000), 4 << 20, Some(0));
    for flujo in (1..20).step_by(2) {
        pedir(&mut c, flujo);
    }
    let (adios, _) = hasta(&mut c, |t| t.tipo == GOAWAY);
    assert_eq!(adios.map(|t| codigo(&t)), Some(Error::Calma as u32),
               "H2-054: 40 MiB construidos y sin salir pasan de los 32: ENHANCE_YOUR_CALM");
    assert!(Trama::leer(&mut c, 1 << 24).is_err(), "H2-054: y el servidor cierra su mitad");
    drop(c);
    hilo.join().unwrap();
}

fn puerto_libre() -> u16 {
    TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port()
}

fn servidor(p: Plazos) -> Servidor {
    let router = Router::nuevo().ruta("GET", "/", "hola").expect("patrón");
    Servidor::nuevo(router).accion("hola", |_| Respuesta::texto("hola")).plazos(p)
}

#[test]
fn http_024_una_conexion_ociosa_entre_peticiones_se_cierra() {
    let puerto = puerto_libre();
    std::thread::spawn(move || servidor(plazos(30_000, 300)).escuchar(puerto));
    let mut c = (0..100)
        .find_map(|_| TcpStream::connect(("127.0.0.1", puerto)).ok().or_else(|| {
            std::thread::sleep(ms(50));
            None
        }))
        .expect("escucha");
    c.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
    c.write_all(b"GET / HTTP/1.1\r\nHost: a\r\n\r\n").unwrap();
    let empezo = Instant::now();
    let mut todo = Vec::new();
    c.read_to_end(&mut todo).expect("HTTP-024: se cierra con un fin, no se queda esperando");
    assert!(todo.starts_with(b"HTTP/1.1 200"), "HTTP-024: la primera petición se atiende");
    assert!(empezo.elapsed() < ms(2_000), "HTTP-024: callar después cierra la conexión dentro del plazo");
}

#[test]
fn http_025_un_plazo_de_cero_no_arranca() {
    for malo in [
        Plazos { sin_avance: Duration::ZERO, ..Plazos::default() },
        Plazos { escritura: Duration::ZERO, ..Plazos::default() },
        Plazos { inactividad: Duration::ZERO, ..Plazos::default() },
    ] {
        let puerto = puerto_libre();
        assert!(servidor(malo).escuchar(puerto).is_err(), "HTTP-025: {malo:?} no arranca");
        assert!(TcpStream::connect(("127.0.0.1", puerto)).is_err(), "HTTP-025: y nadie escucha");
    }
}
