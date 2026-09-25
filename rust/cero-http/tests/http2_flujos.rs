//! Los flujos de HTTP/2: una prueba por requisito de `spec/http2.md` que esta capa puede verificar.
//!
//! La sesión se maneja sin socket a propósito: se le dan tramas y se mira lo que pide hacer. Un
//! servidor de verdad por medio convertiría cada prueba en una carrera, y las carreras esconden
//! justo la clase de fallo que este bloque tiene —el de la 0.8.0 en Java solo aparecía cuando la
//! respuesta le ganaba a la petición—.

use cero_http::http2::flujo::{cabeceras_de_respuesta, Accion, Sesion};
use cero_http::http2::hpack::{Codificador, Decodificador};
use cero_http::http2::trama::*;
use cero_http::http2::{Ajustes, Error, Trama};

fn t(tipo: u8, banderas: u8, flujo: u32, carga: Vec<u8>) -> Trama {
    Trama { tipo, banderas, flujo, carga }
}

fn pares(v: &[(&str, &str)]) -> Vec<(String, String)> {
    v.iter().map(|(n, x)| (n.to_string(), x.to_string())).collect()
}

fn bloque(v: &[(&str, &str)]) -> Vec<u8> {
    Codificador::codificar(&pares(v))
}

/// Un bloque con los nombres **tal cual**, sin pasarlos a minúsculas.
///
/// Hace falta porque el codificador los baja a minúscula a propósito (`H2-033`), así que a través
/// de él es imposible construir la petición que `H2-019` tiene que rechazar. Se escribe el literal
/// de HPACK a mano: nombre nuevo sin indexar, longitud y octetos.
fn bloque_crudo(v: &[(&str, &str)]) -> Vec<u8> {
    let mut salida = Vec::new();
    for (nombre, valor) in v {
        salida.push(0x00);
        salida.push(nombre.len() as u8);
        salida.extend_from_slice(nombre.as_bytes());
        salida.push(valor.len() as u8);
        salida.extend_from_slice(valor.as_bytes());
    }
    salida
}

fn get() -> Vec<(&'static str, &'static str)> {
    vec![(":method", "GET"), (":scheme", "https"), (":path", "/"), (":authority", "a")]
}

fn sesion() -> Sesion {
    Sesion::nueva(Ajustes::default())
}

/// Manda un HEADERS completo y devuelve lo que la sesión pide hacer.
fn pedir(s: &mut Sesion, flujo: u32, cabeceras: &[(&str, &str)], fin: bool) -> Vec<Accion> {
    let banderas = FIN_CABECERAS | if fin { FIN_FLUJO } else { 0 };
    s.recibir(t(HEADERS, banderas, flujo, bloque(cabeceras))).expect("no rompe la conexión").0
}

// ── Entrada y saludo ────────────────────────────────────────────────────────────────────────

/// `H2-001`: lo que no es el preámbulo no se atiende como h2.
#[test]
fn h2_001_el_preambulo_es_el_preambulo() {
    assert!(Sesion::es_preambulo(b"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\nresto"), "H2-001");
    assert!(!Sesion::es_preambulo(b"GET / HTTP/1.1\r\n"), "H2-001");
    assert!(!Sesion::es_preambulo(b"PRI *"), "cortado tampoco");
}

/// `H2-027`: los SETTINGS del servidor son su primera trama, y anuncian el push deshabilitado.
#[test]
fn h2_027_los_settings_del_servidor_van_primero() {
    let primera = Ajustes::default().trama();
    assert_eq!(primera.tipo, SETTINGS, "H2-027");
    assert_eq!(primera.flujo, 0);
    assert_eq!(primera.banderas, 0, "no es un ACK");
    let push = primera.carga.chunks_exact(6).find(|c| c[0..2] == [0, 2]).expect("anuncia push");
    assert_eq!(push[2..6], [0, 0, 0, 0], "push deshabilitado");
}

/// `H2-028`: el PING se devuelve con la misma carga y el ACK puesto, y un ACK no se contesta.
#[test]
fn h2_028_el_ping_vuelve_igual_con_ack() {
    let mut s = sesion();
    let (acciones, _) = s.recibir(t(PING, 0, 0, b"12345678".to_vec())).unwrap();
    match &acciones[0] {
        Accion::Escribir(x) => {
            assert_eq!(x.tipo, PING);
            assert_eq!(x.banderas & RECONOCE, RECONOCE, "H2-028");
            assert_eq!(x.carga, b"12345678", "la misma carga");
        }
        otra => panic!("se esperaba un PING de vuelta, no {otra:?}"),
    }
    let (vacio, _) = s.recibir(t(PING, RECONOCE, 0, b"12345678".to_vec())).unwrap();
    assert!(vacio.is_empty(), "un ACK no se contesta, o son dos que se hacen ping para siempre");
}

// ── Flujos ──────────────────────────────────────────────────────────────────────────────────

/// `H2-007`: los identificadores solo suben. Reusar uno haría ambiguo de qué petición se habla.
#[test]
fn h2_007_los_identificadores_no_retroceden() {
    let mut s = sesion();
    pedir(&mut s, 3, &get(), true);
    let fallo = s
        .recibir(t(HEADERS, FIN_CABECERAS | FIN_FLUJO, 1, bloque(&get())))
        .unwrap_err();
    assert_eq!(fallo.codigo, Error::Protocolo, "H2-007");
}

/// `H2-029` y `H2-030`: dos flujos a la vez, y anular uno deja al otro en pie.
#[test]
fn h2_029_y_030_los_flujos_son_independientes() {
    let mut s = sesion();
    pedir(&mut s, 1, &get(), false);
    pedir(&mut s, 3, &get(), false);
    assert_eq!(s.activos(), 2, "H2-029: los dos a la vez, no en fila");

    let (acciones, _) = s.recibir(Trama::rst(1, Error::Anulado)).unwrap();
    assert_eq!(acciones, vec![Accion::Anulado(1)]);
    assert_eq!(s.activos(), 1, "H2-030");
    // Y el que queda sigue admitiendo cuerpo.
    let (mas, corte) = s.recibir(t(DATA, 0, 3, b"hola".to_vec())).unwrap();
    assert!(corte.is_none(), "H2-030: el flujo 3 no tiene la culpa");
    assert!(matches!(mas[0], Accion::Cuerpo { flujo: 3, .. }));
}

/// `H2-031`: la ventana de un flujo nuevo es la negociada, no los 65 535 del RFC. Tomar el valor
/// del RFC deja al cliente mandando más de lo que este servidor dijo que admitía.
#[test]
fn h2_031_la_ventana_del_flujo_nuevo_es_la_negociada() {
    let ajustes = Ajustes { ventana_inicial: 1_000, ..Ajustes::default() };
    let mut s = Sesion::nueva(ajustes);
    pedir(&mut s, 1, &get(), false);
    assert_eq!(s.ventana_de(1), Some(1_000), "H2-031");
}

/// La ventana inicial que anuncia el cliente es la de **salida**: dice lo que él admite recibir,
/// no lo que nosotros admitimos. Aplicarla a la entrada era dejarle mover por SETTINGS el tope que
/// lo contiene, y además dejaba la salida sin control de flujo, que es lo que h2spec marcaba.
///
/// Cambiarla a mitad mueve la de los flujos ya abiertos, no solo la de los siguientes (§6.9.2): si
/// no, el cliente y el servidor cuentan distinto y uno de los dos corta.
#[test]
fn una_ventana_inicial_nueva_mueve_la_salida_y_no_la_entrada() {
    let mut s = sesion();
    pedir(&mut s, 1, &get(), false);
    // SETTINGS_INITIAL_WINDOW_SIZE = 1000.
    let (acciones, _) = s.recibir(t(SETTINGS, 0, 0, vec![0, 4, 0, 0, 0x03, 0xe8])).unwrap();
    assert!(acciones.contains(&Accion::Reajustar(1_000 - 65_535)), "§6.9.2, hacia la salida");
    assert_eq!(s.ventana_de(1), Some(65_535), "la de entrada es nuestra y no la mueve él");
}

/// Terminar de responder cierra nuestra mitad, no el flujo: hasta que el cliente diga
/// `END_STREAM`, lo que llegue todavía hay que validarlo. Es el fallo que arregló la 0.8.0.
#[test]
fn responder_no_cierra_el_flujo_del_cliente() {
    let mut s = sesion();
    pedir(&mut s, 1, &get(), false);
    s.respondido(1);
    assert_eq!(s.activos(), 1, "sigue vivo hasta el END_STREAM del cliente");

    let (acciones, corte) = s.recibir(t(DATA, FIN_FLUJO, 1, b"tarde".to_vec())).unwrap();
    assert!(corte.is_none(), "el cuerpo que llega después se sigue admitiendo y validando");
    assert!(matches!(acciones[0], Accion::Cuerpo { flujo: 1, fin: true, .. }));
    s.respondido(1);
    assert_eq!(s.activos(), 0, "y ahora sí se suelta");
}

/// `H2-046`: el que ya dijo `END_STREAM` **sí** cuenta para el tope. Parecía al revés —a uno que
/// terminó de hablar no le debemos nada— y es justo ese el que tiene trabajo en marcha: está
/// esperando respuesta. Descontarlos dejaba el tope sin tocar nunca, y lo encontró h2spec, no las
/// pruebas propias, porque hacía falta llenar la conexión de verdad para verlo.
#[test]
fn h2_046_el_que_espera_respuesta_cuenta_para_el_tope() {
    let mut s = sesion();
    pedir(&mut s, 1, &get(), true);
    assert_eq!(s.activos(), 1, "H2-046");
    s.respondido(1);
    assert_eq!(s.activos(), 0, "y deja de contar cuando se le contesta");
}

/// Pasarse del tope de concurrencia corta ese flujo con REFUSED_STREAM, no la conexión: el cliente
/// puede reintentar esa petición sin perder las que ya tenía en vuelo.
#[test]
fn pasarse_del_tope_de_flujos_rechaza_solo_ese() {
    let ajustes = Ajustes { max_flujos: 2, ..Ajustes::default() };
    let mut s = Sesion::nueva(ajustes);
    pedir(&mut s, 1, &get(), false);
    pedir(&mut s, 3, &get(), false);
    let acciones = pedir(&mut s, 5, &get(), false);
    assert_eq!(acciones, vec![Accion::Escribir(Trama::rst(5, Error::Rechazado))]);
    assert_eq!(s.activos(), 2, "la conexión sigue");
}

// ── Bloques de cabeceras ────────────────────────────────────────────────────────────────────

/// `H2-008`: CONTINUATION sin un HEADERS delante no tiene a qué continuar.
#[test]
fn h2_008_continuation_suelta() {
    let fallo = sesion().recibir(t(CONTINUATION, FIN_CABECERAS, 1, vec![0x82])).unwrap_err();
    assert_eq!(fallo.codigo, Error::Protocolo, "H2-008");
}

/// `H2-032`: un bloque mayor que una trama se reparte, y llega entero al otro lado.
#[test]
fn h2_032_un_bloque_repartido_en_continuation() {
    let mut s = sesion();
    let entero = bloque(&get());
    let (a, b) = entero.split_at(3);
    s.recibir(t(HEADERS, 0, 1, a.to_vec())).unwrap();
    let (acciones, _) = s.recibir(t(CONTINUATION, FIN_CABECERAS, 1, b.to_vec())).unwrap();
    match &acciones[0] {
        Accion::Peticion { flujo: 1, cabeceras, .. } => {
            assert_eq!(*cabeceras, pares(&get()), "H2-032");
        }
        otra => panic!("se esperaba la petición entera, no {otra:?}"),
    }
}

/// Entre un HEADERS y su última CONTINUATION no cabe nada, ni siquiera un PING. Si cupiera, dos
/// bloques podrían entrelazarse y HPACK dejaría de tener un orden.
#[test]
fn nada_se_mete_por_medio_de_un_bloque_de_cabeceras() {
    let mut s = sesion();
    s.recibir(t(HEADERS, 0, 1, bloque(&get()))).unwrap();
    let fallo = s.recibir(t(PING, 0, 0, b"12345678".to_vec())).unwrap_err();
    assert_eq!(fallo.codigo, Error::Protocolo);
}

/// `H2-017`: un segundo bloque sin fin de flujo no son trailers, es un error de conexión.
#[test]
fn h2_017_segundo_bloque_sin_fin_de_flujo() {
    let mut s = sesion();
    pedir(&mut s, 1, &get(), false);
    let fallo = s
        .recibir(t(HEADERS, FIN_CABECERAS, 1, bloque(&[("x-trailer", "1")])))
        .unwrap_err();
    assert_eq!(fallo.codigo, Error::Protocolo, "H2-017");
}

/// `H2-035`: los trailers se descartan, **pero se decodifican**. Saltárselos descoloca la tabla de
/// HPACK, y lo que se rompe no es el bloque saltado: son todos los siguientes.
#[test]
fn h2_035_los_trailers_se_decodifican_aunque_se_tiren() {
    let mut s = sesion();
    pedir(&mut s, 1, &get(), false);
    // El trailer indexa un nombre nuevo, que entra en la tabla dinámica del decodificador.
    let trailer = {
        let mut v = vec![0x40, 0x09];
        v.extend_from_slice(b"x-trailer");
        v.push(0x01);
        v.push(b'1');
        v
    };
    let (acciones, corte) = s.recibir(t(HEADERS, FIN_CABECERAS | FIN_FLUJO, 1, trailer)).unwrap();
    assert!(corte.is_none());
    assert_eq!(acciones, vec![Accion::Cuerpo { flujo: 1, datos: Vec::new(), fin: true }], "H2-035");

    // Y la prueba de que la tabla quedó bien: la petición siguiente puede referirse a esa entrada
    // por su índice. Si el bloque se hubiera saltado, el índice 62 no existiría.
    let mut siguiente = bloque(&get());
    siguiente.push(0x80 | 62);
    let (mas, corte) = s.recibir(t(HEADERS, FIN_CABECERAS | FIN_FLUJO, 3, siguiente)).unwrap();
    assert!(corte.is_none(), "la tabla siguió alineada");
    match &mas[0] {
        Accion::Peticion { cabeceras, .. } => {
            assert_eq!(cabeceras.last().unwrap().0, "x-trailer");
        }
        otra => panic!("se esperaba una petición, no {otra:?}"),
    }
}

// ── Peticiones malformadas: cortan el flujo, no la conexión ─────────────────────────────────

fn malformada(cabeceras: &[(&str, &str)]) -> Sesion {
    let mut s = sesion();
    let acciones = s
        .recibir(t(HEADERS, FIN_CABECERAS | FIN_FLUJO, 1, bloque_crudo(cabeceras)))
        .expect("no rompe la conexión")
        .0;
    assert_eq!(
        acciones,
        vec![Accion::Escribir(Trama::rst(1, Error::Protocolo))],
        "tenía que cortar el flujo: {cabeceras:?}"
    );
    s
}

/// `H2-019` a `H2-026`, uno por uno. Todos cortan el flujo y **dejan la conexión en pie**:
/// confundirlos con un error de conexión convierte una petición mal escrita en la caída de todo lo
/// que ese cliente tuviera en vuelo.
#[test]
fn h2_019_a_h2_026_las_peticiones_malformadas() {
    malformada(&[(":method", "GET"), (":scheme", "https"), (":path", "/"), ("Host", "a")]); // H2-019
    malformada(&[(":scheme", "https"), (":path", "/")]); // H2-020: falta :method
    malformada(&[(":method", "GET"), (":path", "/")]); // H2-020: falta :scheme
    malformada(&[(":method", "GET"), (":scheme", "https")]); // H2-020: falta :path
    malformada(&[(":method", "GET"), (":scheme", "https"), (":path", "")]); // H2-021
    malformada(&[(":method", "GET"), (":method", "POST"), (":scheme", "https"), (":path", "/")]); // H2-022
    malformada(&[(":method", "GET"), ("x-una", "1"), (":scheme", "https"), (":path", "/")]); // H2-023
    malformada(&[(":method", "GET"), (":scheme", "https"), (":path", "/"), (":vaya", "1")]); // H2-024
    malformada(&[(":method", "GET"), (":scheme", "https"), (":path", "/"), ("connection", "keep-alive")]); // H2-025
    malformada(&[(":method", "GET"), (":scheme", "https"), (":path", "/"), ("te", "gzip")]); // H2-026

    // Y la conexión aguanta: después de una malformada, la siguiente petición se atiende.
    let mut s = malformada(&[(":scheme", "https"), (":path", "/")]);
    let acciones = pedir(&mut s, 3, &get(), true);
    assert!(matches!(acciones[0], Accion::Peticion { flujo: 3, .. }), "la conexión sigue viva");
}

/// `H2-026` admite `trailers`, que es el único valor que el RFC deja pasar.
#[test]
fn h2_026_te_trailers_si_vale() {
    let mut s = sesion();
    let mut c = get();
    c.push(("te", "trailers"));
    assert!(matches!(pedir(&mut s, 1, &c, true)[0], Accion::Peticion { .. }), "H2-026");
}

/// `H2-018`: un método que el servidor no implementa es cosa del ruteo —un 501—, no de la capa de
/// tramas. La petición tiene que **llegar**: si se cortara aquí, la conexión pagaría por ella.
#[test]
fn h2_018_un_metodo_desconocido_llega_al_ruteo() {
    let mut s = sesion();
    let c = vec![(":method", "INVENTADO"), (":scheme", "https"), (":path", "/"), (":authority", "a")];
    let acciones = pedir(&mut s, 1, &c, true);
    assert!(matches!(acciones[0], Accion::Peticion { .. }), "H2-018");
}

// ── Control de flujo ────────────────────────────────────────────────────────────────────────

/// Pasarse de la ventana es error de conexión, y el crédito se devuelve en cuanto se ha leído:
/// esperar a que la aplicación consuma el cuerpo convierte un handler lento en un atasco de todo.
#[test]
fn el_credito_se_devuelve_al_leer_y_pasarse_corta() {
    let mut s = sesion();
    pedir(&mut s, 1, &get(), false);
    let (acciones, _) = s.recibir(t(DATA, 0, 1, vec![b'x'; 100])).unwrap();
    assert!(acciones.contains(&Accion::Escribir(Trama::ventana(0, 100))), "ventana de conexión");
    assert!(acciones.contains(&Accion::Escribir(Trama::ventana(1, 100))), "ventana del flujo");
    assert_eq!(s.ventana_de(1), Some(65_535), "el flujo vuelve a estar entero");

    let ajustes = Ajustes { ventana_inicial: 10, ..Ajustes::default() };
    let mut apretada = Sesion::nueva(ajustes);
    pedir(&mut apretada, 1, &get(), false);
    let fallo = apretada.recibir(t(DATA, 0, 1, vec![b'x'; 11])).unwrap_err();
    assert_eq!(fallo.codigo, Error::ControlDeFlujo);
}

/// Un cuerpo después de `END_STREAM` corta ese flujo con STREAM_CLOSED y no la conexión.
#[test]
fn cuerpo_despues_del_fin_de_flujo() {
    let mut s = sesion();
    pedir(&mut s, 1, &get(), true);
    let (acciones, corte) = s.recibir(t(DATA, 0, 1, b"tarde".to_vec())).unwrap();
    assert_eq!(corte.unwrap().codigo, Error::FlujoCerrado);
    assert_eq!(acciones, vec![Accion::Escribir(Trama::rst(1, Error::FlujoCerrado))]);
}

/// El relleno se descuenta de la ventana pero no llega a la aplicación, y uno mayor que la carga es
/// un error de protocolo, no un recorte silencioso.
#[test]
fn el_relleno_ni_llega_ni_se_perdona() {
    let mut s = sesion();
    pedir(&mut s, 1, &get(), false);
    let mut carga = vec![3u8];
    carga.extend_from_slice(b"hola");
    carga.extend_from_slice(&[0, 0, 0]);
    let (acciones, _) = s.recibir(t(DATA, RELLENO, 1, carga)).unwrap();
    assert!(matches!(&acciones[0], Accion::Cuerpo { datos, .. } if datos == b"hola"));

    let fallo = s.recibir(t(DATA, RELLENO, 1, vec![200, b'x'])).unwrap_err();
    assert_eq!(fallo.codigo, Error::Protocolo);
}

// ── Inundaciones ────────────────────────────────────────────────────────────────────────────

/// `H2-036` — CVE-2024-27316. Cada CONTINUATION es válida; lo que no lo es, es que no acaben.
#[test]
fn h2_036_un_bloque_de_cabeceras_sin_fin() {
    let mut s = sesion();
    s.topes.bloque_cabeceras = 4_096;
    s.recibir(t(HEADERS, 0, 1, bloque(&get()))).unwrap();
    let mut fallo = None;
    for _ in 0..100 {
        if let Err(e) = s.recibir(t(CONTINUATION, 0, 1, vec![0u8; 512])) {
            fallo = Some(e);
            break;
        }
    }
    assert_eq!(fallo.expect("tenía que cortar").codigo, Error::Calma, "H2-036");
}

/// `H2-037` — CVE-2023-44487. Abrir y anular pide trabajo sin coste propio: cada par de tramas
/// deja al servidor con una petición empezada y al cliente sin nada que esperar.
#[test]
fn h2_037_abrir_y_anular_en_bucle() {
    let mut s = sesion();
    s.topes.anulados = 10;
    let mut fallo = None;
    let mut id = 1;
    for _ in 0..50 {
        pedir(&mut s, id, &get(), false);
        if let Err(e) = s.recibir(Trama::rst(id, Error::Anulado)) {
            fallo = Some(e);
            break;
        }
        id += 2;
    }
    assert_eq!(fallo.expect("tenía que cortar").codigo, Error::Calma, "H2-037");
}

/// `H2-038`: las tramas de control no abren flujos, así que nada salvo un tope las limita. Y el
/// contador se reinicia con cada flujo nuevo: un cliente que trabaja no debe chocar nunca.
#[test]
fn h2_038_tramas_de_control_sin_abrir_nada() {
    let mut s = sesion();
    s.topes.control_sin_flujo = 20;
    let mut fallo = None;
    for _ in 0..100 {
        if let Err(e) = s.recibir(t(PING, 0, 0, b"12345678".to_vec())) {
            fallo = Some(e);
            break;
        }
    }
    assert_eq!(fallo.expect("tenía que cortar").codigo, Error::Calma, "H2-038");

    let mut trabajando = sesion();
    trabajando.topes.control_sin_flujo = 20;
    let mut id = 1;
    for _ in 0..100 {
        trabajando.recibir(t(PING, 0, 0, b"12345678".to_vec())).expect("un cliente que trabaja");
        pedir(&mut trabajando, id, &get(), true);
        trabajando.respondido(id);
        id += 2;
    }
}

// ── Respuestas ──────────────────────────────────────────────────────────────────────────────

/// `H2-033` y `H2-034`. La segunda no es cosmética: un `Connection: keep-alive` emitido en h2 hace
/// que un proxy que traduzca a HTTP/1.1 escriba una conexión que no existe.
#[test]
fn h2_033_y_034_las_cabeceras_de_la_respuesta() {
    let salida = cabeceras_de_respuesta(
        200,
        &pares(&[
            ("Content-Type", "text/html"),
            ("Connection", "keep-alive"),
            ("Transfer-Encoding", "chunked"),
            ("X-Propia", "1"),
        ]),
    );
    let leidas = Decodificador::nuevo(4096, 64 * 1024).decodificar(&salida).unwrap();
    let nombres: Vec<&str> = leidas.iter().map(|(n, _)| n.as_str()).collect();
    assert_eq!(nombres, vec![":status", "content-type", "x-propia"], "H2-033 y H2-034");
    assert_eq!(leidas[0].1, "200");
}

// ── Los dos juegos de ajustes ───────────────────────────────────────────────────────────────

/// `H2-042`: los ajustes del cliente dicen lo que **él** admite recibir. Tomarlos por los nuestros
/// le deja subir por SETTINGS los topes que lo contienen, y es el más grave de los del bloque
/// porque no parece un fallo de seguridad al leerlo: con `SETTINGS_MAX_HEADER_LIST_SIZE` a 2^32-1
/// desaparece `H2-039`, y con `SETTINGS_MAX_CONCURRENT_STREAMS`, el tope de flujos en vuelo. Dos
/// defensas anuladas por una trama válida de seis octetos.
#[test]
fn h2_042_los_ajustes_del_cliente_no_mueven_los_nuestros() {
    let mut s = sesion();
    let nuestros = s.ajustes;
    // MAX_CONCURRENT_STREAMS, MAX_HEADER_LIST_SIZE y MAX_FRAME_SIZE, los tres al máximo.
    let carga = vec![
        0, 3, 0xff, 0xff, 0xff, 0xff,
        0, 6, 0xff, 0xff, 0xff, 0xff,
        0, 5, 0x00, 0xff, 0xff, 0xff,
    ];
    s.recibir(t(SETTINGS, 0, 0, carga)).unwrap();

    assert_eq!(s.ajustes.max_flujos, nuestros.max_flujos, "H2-042: el tope de flujos es nuestro");
    assert_eq!(s.ajustes.max_cabeceras, nuestros.max_cabeceras, "H2-042: y el de cabeceras");
    assert_eq!(s.ajustes.max_trama, nuestros.max_trama, "H2-042: y lo que admitimos leer");
    assert_eq!(s.par.max_trama, 0x00ff_ffff, "los suyos sí se guardan: rigen lo que se le manda");
}

/// Hasta que el cliente diga los suyos hay que suponerle los del RFC, no los nuestros. Suponer los
/// nuestros sería mandarle tramas de un tamaño que no ha aceptado.
#[test]
fn h2_042_al_cliente_se_le_suponen_los_del_rfc() {
    let s = sesion();
    assert_eq!(s.par.max_trama, 16_384);
    assert_eq!(s.par.ventana_inicial, 65_535);
}

/// Un WINDOW_UPDATE es crédito para mandar, no un apunte que se lee y se tira. Sin esto la salida
/// no tiene control de flujo, que es la mitad de lo que h2spec marcaba.
#[test]
fn h2_040_un_window_update_es_credito_de_salida() {
    let mut s = sesion();
    pedir(&mut s, 1, &get(), false);
    let (acciones, _) = s.recibir(t(WINDOW_UPDATE, 0, 1, vec![0, 0, 0x10, 0])).unwrap();
    assert_eq!(acciones, vec![Accion::Credito { flujo: 1, cuanto: 4_096 }]);
    let (conexion, _) = s.recibir(t(WINDOW_UPDATE, 0, 0, vec![0, 0, 0x10, 0])).unwrap();
    assert_eq!(conexion, vec![Accion::Credito { flujo: 0, cuanto: 4_096 }]);
}

// ── Lo que encontró h2spec y las pruebas propias no ─────────────────────────────────────────

/// `H2-043`: un cuerpo que no mide lo que dijo `content-length` es el contrabando de HTTP/1.1 otra
/// vez, ahora en binario. Pasarse se sabe al momento; quedarse corto, solo al cerrar el flujo.
#[test]
fn h2_043_el_cuerpo_tiene_que_medir_lo_que_dijo() {
    for (declarado, cuerpo, fin) in [("5", "hola", true), ("2", "hola", false)] {
        let mut s = sesion();
        let mut cabeceras = get();
        cabeceras[0] = (":method", "POST");
        cabeceras.push(("content-length", declarado));
        pedir(&mut s, 1, &cabeceras, false);
        let banderas = if fin { FIN_FLUJO } else { 0 };
        let (acciones, corte) = s.recibir(t(DATA, banderas, 1, cuerpo.as_bytes().to_vec())).unwrap();
        assert_eq!(corte.expect("H2-043").codigo, Error::Protocolo);
        assert_eq!(acciones, vec![Accion::Escribir(Trama::rst(1, Error::Protocolo))]);
    }
}

/// Y el que cuadra pasa: la comprobación no puede costarle nada a una petición normal.
#[test]
fn h2_043_el_cuerpo_que_cuadra_pasa() {
    let mut s = sesion();
    let mut cabeceras = get();
    cabeceras[0] = (":method", "POST");
    cabeceras.push(("content-length", "4"));
    pedir(&mut s, 1, &cabeceras, false);
    let (_, corte) = s.recibir(t(DATA, FIN_FLUJO, 1, b"hola".to_vec())).unwrap();
    assert!(corte.is_none());
}

/// `H2-044` y `H2-049`: PRIORITY está deprecada y se descarta, pero su forma se sigue mirando. Un
/// flujo que depende de sí mismo no describe un árbol, y sobre el flujo 0 no describe nada.
#[test]
fn h2_044_y_049_la_prioridad_se_descarta_pero_se_mira() {
    let mut s = sesion();
    pedir(&mut s, 1, &get(), false);
    let (acciones, corte) = s.recibir(t(PRIORITY, 0, 1, vec![0, 0, 0, 1, 16])).unwrap();
    assert_eq!(corte.expect("H2-044").codigo, Error::Protocolo);
    assert_eq!(acciones, vec![Accion::Escribir(Trama::rst(1, Error::Protocolo))]);

    let fallo = s.recibir(t(PRIORITY, 0, 0, vec![0, 0, 0, 1, 16])).unwrap_err();
    assert_eq!(fallo.codigo, Error::Protocolo, "H2-049");

    let mut otra = sesion();
    let (vacio, _) = otra.recibir(t(PRIORITY, 0, 1, vec![0, 0, 0, 3, 16])).unwrap();
    assert!(vacio.is_empty(), "una prioridad bien formada se lee y se tira");
}

/// `H2-045`: a un flujo que todavía no ha existido no se le puede dar crédito. Ignorarlo dejaba al
/// cliente esperando una respuesta que no iba a llegar, y h2spec lo veía como un plantón.
#[test]
fn h2_045_no_hay_credito_para_un_flujo_ocioso() {
    let mut s = sesion();
    let fallo = s.recibir(t(WINDOW_UPDATE, 0, 7, vec![0, 0, 0x10, 0])).unwrap_err();
    assert_eq!(fallo.codigo, Error::Protocolo, "H2-045");
}

/// `H2-047`: el cliente ya dijo `END_STREAM`, así que por su lado no queda nada por decir. Ni
/// siquiera trailers: los trailers van antes del fin, no después.
#[test]
fn h2_047_no_hay_cabeceras_despues_del_fin() {
    let mut s = sesion();
    pedir(&mut s, 1, &get(), true);
    let acciones = pedir(&mut s, 1, &[("x", "y")], true);
    assert_eq!(acciones, vec![Accion::Escribir(Trama::rst(1, Error::FlujoCerrado))], "H2-047");
}

/// `H2-035`: los trailers de verdad —antes del fin— sí valen, y se decodifican aunque se tiren.
#[test]
fn h2_035_los_trailers_antes_del_fin_siguen_valiendo() {
    let mut s = sesion();
    pedir(&mut s, 1, &get(), false);
    let acciones = pedir(&mut s, 1, &[("x-final", "1")], true);
    assert_eq!(acciones, vec![Accion::Cuerpo { flujo: 1, datos: Vec::new(), fin: true }]);
}
