//! Una prueba por requisito de `spec/http2.md` que la capa de tramas puede verificar sola.

use cero_http::http2::trama::*;
use cero_http::http2::{Ajustes, Error, Trama};
use std::io::Cursor;

fn bytes(tipo: u8, banderas: u8, flujo: u32, carga: &[u8]) -> Vec<u8> {
    let n = carga.len();
    let mut v = vec![(n >> 16) as u8, (n >> 8) as u8, n as u8, tipo, banderas];
    v.extend_from_slice(&flujo.to_be_bytes());
    v.extend_from_slice(carga);
    v
}

fn leer(tipo: u8, banderas: u8, flujo: u32, carga: &[u8]) -> Trama {
    let v = bytes(tipo, banderas, flujo, carga);
    Trama::leer(&mut Cursor::new(v), 16_384).expect("se lee")
}

#[test]
fn h2_004_data_sobre_el_flujo_cero() {
    let e = leer(DATA, 0, 0, b"x").comprobar().unwrap_err();
    assert_eq!(e.codigo, Error::Protocolo, "H2-004");
}

#[test]
fn h2_005_y_006_headers_sobre_flujo_cero_o_par() {
    assert_eq!(leer(HEADERS, 0, 0, b"").comprobar().unwrap_err().codigo, Error::Protocolo, "H2-005");
    assert_eq!(leer(HEADERS, 0, 2, b"").comprobar().unwrap_err().codigo, Error::Protocolo, "H2-006");
    assert!(leer(HEADERS, 0, 1, b"").comprobar().is_ok(), "un flujo impar sí vale");
}

#[test]
fn h2_002_y_003_settings() {
    assert_eq!(leer(SETTINGS, 0, 1, b"").comprobar().unwrap_err().codigo, Error::Protocolo, "H2-002");
    let cinco = leer(SETTINGS, 0, 0, b"12345").comprobar().unwrap_err();
    assert_eq!(cinco.codigo, Error::TamanoDeTrama, "H2-003");
    assert!(leer(SETTINGS, 0, 0, &[0; 12]).comprobar().is_ok(), "doce sí es múltiplo de seis");
    // Un ACK con carga es un error de tamaño, no de protocolo.
    assert_eq!(leer(SETTINGS, RECONOCE, 0, &[0; 6]).comprobar().unwrap_err().codigo,
               Error::TamanoDeTrama);
}

#[test]
fn h2_009_un_cliente_no_promete() {
    assert_eq!(leer(PUSH_PROMISE, 0, 1, &[0; 4]).comprobar().unwrap_err().codigo,
               Error::Protocolo, "H2-009");
}

#[test]
fn h2_010_y_011_ping() {
    assert_eq!(leer(PING, 0, 1, &[0; 8]).comprobar().unwrap_err().codigo, Error::Protocolo, "H2-011");
    assert_eq!(leer(PING, 0, 0, &[0; 7]).comprobar().unwrap_err().codigo,
               Error::TamanoDeTrama, "H2-010");
    assert!(leer(PING, 0, 0, &[0; 8]).comprobar().is_ok());
}

#[test]
fn h2_012_y_013_window_update() {
    assert_eq!(leer(WINDOW_UPDATE, 0, 0, &[0; 3]).comprobar().unwrap_err().codigo,
               Error::TamanoDeTrama, "H2-012");
    assert_eq!(leer(WINDOW_UPDATE, 0, 0, &[0; 4]).comprobar().unwrap_err().codigo,
               Error::Protocolo, "H2-013: incremento cero sobre la conexión");
    assert!(leer(WINDOW_UPDATE, 0, 0, &[0, 0, 0, 1]).comprobar().is_ok());
}

#[test]
fn h2_014_una_trama_mayor_que_el_maximo() {
    // La cabecera anuncia 20 000 pero el máximo es 16 384: se rechaza **antes** de leer la
    // carga, porque si no, anunciar 16 MB bastaría para que el servidor los reservara.
    let mut v = vec![0x00, 0x4e, 0x20, DATA, 0, 0, 0, 0, 1];
    v.extend_from_slice(&[0u8; 100]);
    let e = Trama::leer(&mut Cursor::new(v), 16_384).unwrap_err();
    assert_eq!(e.codigo, Error::TamanoDeTrama, "H2-014");
}

#[test]
fn el_bit_reservado_del_identificador_se_ignora_no_se_rechaza() {
    let mut v = bytes(PING, 0, 0, &[0; 8]);
    v[5] |= 0x80;   // bit R
    let t = Trama::leer(&mut Cursor::new(v), 16_384).expect("se lee");
    assert_eq!(t.flujo, 0, "el bit reservado no forma parte del identificador");
}

#[test]
fn ida_y_vuelta_de_una_trama() {
    let t = Trama { tipo: DATA, banderas: FIN_FLUJO, flujo: 7, carga: b"hola".to_vec() };
    let mut salida = Vec::new();
    t.escribir(&mut salida).unwrap();
    let vuelta = Trama::leer(&mut Cursor::new(salida), 16_384).unwrap();
    assert_eq!(vuelta.tipo, DATA);
    assert_eq!(vuelta.flujo, 7);
    assert!(vuelta.fin_flujo());
    assert_eq!(vuelta.carga, b"hola");
}

#[test]
fn h2_027_los_ajustes_del_servidor_anuncian_push_deshabilitado() {
    let t = Ajustes::default().trama();
    assert_eq!(t.tipo, SETTINGS);
    assert_eq!(t.flujo, 0);
    assert_eq!(t.carga.len() % 6, 0);
    let push = t.carga.chunks_exact(6).find(|c| u16::from_be_bytes([c[0], c[1]]) == 0x2);
    assert_eq!(push.map(|c| u32::from_be_bytes([c[2], c[3], c[4], c[5]])), Some(0),
               "H2-027: push deshabilitado, que es lo que manda el RFC");
}

#[test]
fn h2_031_la_ventana_inicial_negociada_da_el_delta() {
    let mut a = Ajustes::default();
    assert_eq!(a.ventana_inicial, 65_535);
    // El cliente pide un mega: los flujos ya abiertos tienen que sumar la diferencia, no
    // quedarse con la del RFC.
    let carga = [0x00, 0x04, 0x00, 0x10, 0x00, 0x00];
    let delta = a.aplicar(&carga).unwrap();
    assert_eq!(a.ventana_inicial, 1_048_576);
    assert_eq!(delta, 1_048_576 - 65_535, "H2-031");
}

#[test]
fn los_ajustes_fuera_de_rango_se_rechazan() {
    let mut a = Ajustes::default();
    // MAX_FRAME_SIZE por debajo del mínimo del RFC.
    assert!(a.aplicar(&[0x00, 0x05, 0x00, 0x00, 0x10, 0x00]).is_err());
    // ENABLE_PUSH que no es 0 ni 1.
    assert!(a.aplicar(&[0x00, 0x02, 0x00, 0x00, 0x00, 0x07]).is_err());
    // Ventana inicial por encima de 2^31-1.
    assert!(a.aplicar(&[0x00, 0x04, 0xff, 0xff, 0xff, 0xff]).is_err());
}

#[test]
fn un_ajuste_desconocido_se_ignora() {
    let mut a = Ajustes::default();
    // Ignorar lo desconocido es lo que permite extender el protocolo sin romper a quien no lo
    // conoce. Rechazarlo rompería a los clientes nuevos contra servidores viejos.
    assert!(a.aplicar(&[0xff, 0xff, 0x00, 0x00, 0x00, 0x01]).is_ok());
}
