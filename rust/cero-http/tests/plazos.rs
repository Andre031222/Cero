use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use std::io::{BufReader, Cursor};

use cero_http::http2::hpack::{Codificador, Decodificador};
use cero_http::http2::trama::{
    DATA, FIN_CABECERAS, FIN_FLUJO, GOAWAY, HEADERS, PING, PRIORITY, RST_STREAM, SETTINGS,
};
use cero_http::http2::{self, Ajustes, Error, Trama};
use cero_http::peticion::{self, Rechazo};
use cero_http::{Limites, Peticion, Plazos, Respuesta, Router, Servidor};

const GET_RAIZ: [u8; 3] = [0x82, 0x86, 0x84];

fn ms(n: u64) -> Duration {
    Duration::from_millis(n)
}

fn plazos(escritura: u64, inactividad: u64) -> Plazos {
    Plazos { sin_avance: ms(30_000), escritura: ms(escritura), inactividad: ms(inactividad) }
}

fn h2(plazos: Plazos, cuerpo: usize, ventana_inicial: Option<u32>) -> (TcpStream, JoinHandle<()>) {
    con_limites(plazos, Limites::default(), move |_| Respuesta::texto(&"x".repeat(cuerpo)), ventana_inicial)
}

fn con_limites(
    plazos: Plazos,
    limites: Limites,
    atender: impl Fn(Peticion) -> Respuesta + Send + Sync + 'static,
    ventana_inicial: Option<u32>,
) -> (TcpStream, JoinHandle<()>) {
    let oyente = TcpListener::bind("127.0.0.1:0").expect("puerto");
    let puerto = oyente.local_addr().unwrap().port();
    let hilo = std::thread::spawn(move || {
        let (socket, _) = oyente.accept().expect("conexión");
        let _ = http2::conexion::servir(socket, Ajustes::default(), Arc::new(atender), plazos, limites);
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
    let mut octetos = Vec::new();
    trama.escribir(&mut octetos).unwrap();
    latir_crudo(cliente, cada, octetos)
}

fn latir_crudo(cliente: &TcpStream, cada: Duration, octetos: Vec<u8>) -> (Arc<AtomicBool>, JoinHandle<()>) {
    let parar = Arc::new(AtomicBool::new(false));
    let suyo = Arc::clone(&parar);
    let mut socket = cliente.try_clone().unwrap();
    let hilo = std::thread::spawn(move || {
        while !suyo.load(Ordering::SeqCst) && socket.write_all(&octetos).is_ok() {
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
fn h2_053_lo_que_no_es_trabajo_no_reinicia_la_inactividad() {
    let (mut c, hilo) = h2(plazos(30_000, 300), 4, None);
    let latidos = [
        Trama { tipo: PING, banderas: 0, flujo: 0, carga: b"12345678".to_vec() },
        Trama::ventana(0, 1),
        Trama { tipo: SETTINGS, banderas: 0, flujo: 0, carga: Vec::new() },
        Trama { tipo: PRIORITY, banderas: 0, flujo: 3, carga: vec![0, 0, 0, 0, 15] },
    ];
    let mut octetos = Vec::new();
    for t in &latidos {
        t.escribir(&mut octetos).unwrap();
    }
    let (parar, latido) = latir_crudo(&c, ms(100), octetos);
    let empezo = Instant::now();
    let (adios, _) = hasta(&mut c, |t| t.tipo == GOAWAY);
    parar.store(true, Ordering::SeqCst);
    latido.join().unwrap();
    assert_eq!(adios.map(|t| codigo(&t)), Some(0),
               "H2-053: PING, WINDOW_UPDATE, SETTINGS y PRIORITY no son trabajo, y la conexión se despide");
    assert!(empezo.elapsed() < ms(2_000), "H2-053: dentro del plazo de inactividad");
    assert!(empezo.elapsed() >= ms(225), "H2-053: y no antes");
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

#[test]
fn h2_054_una_respuesta_sola_pasa_aunque_supere_el_presupuesto() {
    let limites = Limites { en_vuelo: 1 << 20, ..Limites::default() };
    let (mut c, hilo) = con_limites(plazos(30_000, 30_000), limites, |_| Respuesta::texto(&"x".repeat(4 << 20)), None);
    Trama::ventana(0, 0x7fff_ffff - 65_535).escribir(&mut c).unwrap();
    Trama { tipo: SETTINGS, banderas: 0, flujo: 0, carga: vec![0, 4, 0x7f, 0xff, 0xff, 0xff] }.escribir(&mut c).unwrap();
    pedir(&mut c, 1);
    let (fin, datos) = hasta(&mut c, |t| t.tipo == GOAWAY || (t.tipo == DATA && t.banderas & FIN_FLUJO != 0));
    assert_eq!(fin.map(|t| t.tipo), Some(DATA), "H2-054: sin GOAWAY");
    assert_eq!(datos, 4 << 20, "H2-054: con nada más en vuelo, 4 MiB salen enteros con un presupuesto de 1 MiB");
    drop(c);
    hilo.join().unwrap();
}

#[test]
fn h2_055_un_cuerpo_de_mas_recibe_413() {
    let limites = Limites { cuerpo: 100, ..Limites::default() };
    let (mut c, hilo) = con_limites(plazos(30_000, 30_000), limites, |p| Respuesta::texto(&String::from_utf8_lossy(&p.cuerpo)), None);
    let cabeceras = [(":method", "POST"), (":scheme", "http"), (":path", "/")]
        .map(|(n, v)| (n.to_string(), v.to_string()));
    Trama { tipo: HEADERS, banderas: FIN_CABECERAS, flujo: 1, carga: Codificador::codificar(&cabeceras) }
        .escribir(&mut c)
        .unwrap();
    for _ in 0..2 {
        Trama { tipo: DATA, banderas: 0, flujo: 1, carga: vec![b'x'; 60] }.escribir(&mut c).unwrap();
    }
    let (respuesta, _) = hasta(&mut c, |t| t.tipo == HEADERS);
    let respuesta = respuesta.expect("H2-055: hay respuesta");
    let estado = Decodificador::nuevo(4096, 1 << 20).decodificar(&respuesta.carga).unwrap()[0].1.clone();
    assert_eq!((respuesta.flujo, estado.as_str()), (1, "413"), "H2-055: pasar de 100 octetos de cuerpo es 413");
    assert!(respuesta.banderas & FIN_FLUJO != 0, "H2-055: con fin de flujo");
    let (corte, _) = hasta(&mut c, |t| t.tipo == RST_STREAM);
    assert_eq!(corte.map(|t| (t.flujo, codigo(&t))), Some((1, 0)), "H2-055: y RST_STREAM NO_ERROR");

    for _ in 0..4 {
        Trama { tipo: DATA, banderas: 0, flujo: 1, carga: vec![b'x'; 16_380] }.escribir(&mut c).unwrap();
    }
    Trama { tipo: HEADERS, banderas: FIN_CABECERAS, flujo: 3, carga: Codificador::codificar(&cabeceras) }
        .escribir(&mut c)
        .unwrap();
    Trama { tipo: DATA, banderas: FIN_FLUJO, flujo: 3, carga: vec![b'y'; 90] }.escribir(&mut c).unwrap();
    let (eco, datos) = hasta(&mut c, |t| t.flujo == 3 && (t.tipo == DATA && t.banderas & FIN_FLUJO != 0 || t.tipo == GOAWAY));
    assert!(eco.is_some_and(|t| t.tipo == DATA) && datos >= 90,
            "H2-055: lo que el cliente ya tenía en vuelo tras el 413 devuelve su ventana, y la conexión sigue subiendo");

    let mut anunciadas = cabeceras.to_vec();
    anunciadas.push(("content-length".to_string(), "1000".to_string()));
    Trama { tipo: HEADERS, banderas: FIN_CABECERAS, flujo: 5, carga: Codificador::codificar(&anunciadas) }
        .escribir(&mut c)
        .unwrap();
    let (anunciado, _) = hasta(&mut c, |t| t.flujo == 5);
    let anunciado = anunciado.expect("H2-055: responde al content-length de más");
    let estado = Decodificador::nuevo(4096, 1 << 20).decodificar(&anunciado.carga).unwrap()[0].1.clone();
    assert_eq!(estado, "413", "H2-055: un content-length de más es 413 antes de mandar nada");
    drop(c);
    hilo.join().unwrap();
}

#[test]
fn h2_038_una_descarga_larga_no_es_una_inundacion() {
    let mut s = http2::Sesion::nueva(Ajustes::default());
    let pedido = Trama { tipo: HEADERS, banderas: FIN_FLUJO | FIN_CABECERAS, flujo: 1, carga: GET_RAIZ.to_vec() };
    assert!(s.recibir(pedido).is_ok());
    for _ in 0..4_000 {
        assert!(s.recibir(Trama::ventana(0, 16_384)).is_ok(), "H2-038: el crédito de una descarga en curso no es control vacío");
        assert!(s.recibir(Trama::ventana(1, 16_384)).is_ok(), "H2-038: ni el del flujo");
    }
}

#[test]
fn http_026_un_cuerpo_de_mas_no_se_lee() {
    let leer = |crudo: &str, max: usize| peticion::leer(&mut BufReader::new(Cursor::new(crudo.as_bytes().to_vec())), max);
    let anunciado = leer("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 11534336\r\n\r\n", 10 << 20);
    assert_eq!(anunciado.unwrap_err(), Rechazo::CuerpoGrande, "HTTP-026: 11 MiB anunciados, 413 sin leerlos");
    assert_eq!(Rechazo::CuerpoGrande.estado(), 413, "HTTP-026");
    assert!(leer("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 4\r\n\r\nhola", 4).is_ok(), "HTTP-026: lo que cabe se lee");
    let trozos = "POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nhol\r\n2\r\nas\r\n0\r\n\r\n";
    assert_eq!(leer(trozos, 4).unwrap_err(), Rechazo::CuerpoGrande, "HTTP-026: por trozos cuenta la suma");
}

fn puerto_libre() -> u16 {
    TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port()
}

fn servidor(p: Plazos) -> Servidor {
    let router = Router::nuevo().ruta("GET", "/", "hola").expect("patrón");
    Servidor::nuevo(router).accion("hola", |_| Respuesta::texto("hola")).plazos(p).direccion("127.0.0.1")
}

#[test]
fn http_024_una_conexion_ociosa_entre_peticiones_se_cierra() {
    let puerto = puerto_libre();
    std::thread::spawn(move || servidor(plazos(30_000, 400)).escuchar(puerto));
    let mut c = (0..100)
        .find_map(|_| TcpStream::connect(("127.0.0.1", puerto)).ok().or_else(|| {
            std::thread::sleep(ms(50));
            None
        }))
        .expect("escucha");
    c.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
    let mut respuesta = [0u8; 512];
    c.write_all(b"GET / HTTP/1.1\r\nHost: a\r\n\r\n").unwrap();
    let n = c.read(&mut respuesta).unwrap();
    let primera = String::from_utf8_lossy(&respuesta[..n]).to_string();
    assert!(primera.starts_with("HTTP/1.1 200") && !primera.contains("Connection: close"),
            "HTTP-024: la primera petición se atiende y la conexión sigue");
    std::thread::sleep(ms(200));
    c.write_all(b"GET / HTTP/1.1\r\nHost: a\r\n\r\n").unwrap();
    let n = c.read(&mut respuesta).unwrap();
    assert!(respuesta[..n].starts_with(b"HTTP/1.1 200"), "HTTP-024: una segunda a media inactividad se atiende");
    let empezo = Instant::now();
    let mut resto = Vec::new();
    c.read_to_end(&mut resto).expect("HTTP-024: se cierra con un fin, no se queda esperando");
    assert!(resto.is_empty() && empezo.elapsed() < ms(2_000), "HTTP-024: callar después cierra la conexión");
    assert!(empezo.elapsed() >= ms(300), "HTTP-024: y no antes de la inactividad");
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
    for malo in [Limites { en_vuelo: 0, ..Limites::default() }, Limites { cuerpo: 0, ..Limites::default() }] {
        let puerto = puerto_libre();
        assert!(servidor(Plazos::default()).limites(malo).escuchar(puerto).is_err(), "HTTP-025: {malo:?} no arranca");
    }
}
