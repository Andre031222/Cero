//! Cada prueba cita el requisito de `spec/sesiones.md` que verifica.
//!
//! No están copiadas de la batería de Java: están escritas leyendo el contrato. Si el contrato
//! fuera una descripción de Java con otras palabras, aquí se notaría.

use cero_http::sesion::{self, Almacen};
use std::sync::Arc;
use std::time::Duration;

fn almacen() -> Almacen {
    Almacen::nuevo(Duration::from_secs(600), None)
}

#[test]
fn ses_001_sin_cookie_no_recupera_nada() {
    let a = almacen();
    a.crear().unwrap();
    assert!(a.recuperar(None).is_none(), "SES-001");
    assert!(a.recuperar(Some("inventado")).is_none(), "SES-001");
}

#[test]
fn ses_002_el_identificador_es_largo() {
    let a = almacen();
    let s = a.crear().unwrap();
    let id = s.lock().unwrap().id().to_string();
    assert!(id.len() >= 40, "SES-002: {} caracteres", id.len());
}

#[test]
fn ses_003_dos_sesiones_no_comparten_identificador() {
    let a = almacen();
    let uno = a.crear().unwrap().lock().unwrap().id().to_string();
    let dos = a.crear().unwrap().lock().unwrap().id().to_string();
    assert_ne!(uno, dos, "SES-003");
}

#[test]
fn ses_004_invalidar_deja_la_sesion_inutilizable() {
    let a = almacen();
    let s = a.crear().unwrap();
    let mut g = s.lock().unwrap();
    g.poner("quien", "andre").unwrap();
    g.invalidar();
    assert!(g.poner("quien", "otro").is_err(), "SES-004: escribir debe fallar");
    assert!(g.leer("quien").is_err(), "SES-004: leer debe fallar");
}

#[test]
fn ses_005_rotar_cambia_el_id_y_conserva_los_atributos() {
    let a = almacen();
    let s = a.crear().unwrap();
    let viejo = s.lock().unwrap().id().to_string();
    s.lock().unwrap().poner("quien", "andre").unwrap();
    s.lock().unwrap().cookie_pendiente(); // la de creación, ya emitida

    let nuevo = a.rotar(&s).unwrap();
    assert_ne!(viejo, nuevo, "SES-005: el identificador cambia");
    assert_eq!(s.lock().unwrap().leer("quien").unwrap().map(String::as_str), Some("andre"),
               "SES-005: los atributos se conservan");
    assert!(s.lock().unwrap().cookie_pendiente().is_some(), "SES-005: obliga a reemitir");
    assert!(a.recuperar(Some(&nuevo)).is_some(), "SES-005: se recupera por el nuevo");
    assert!(a.recuperar(Some(&viejo)).is_none(), "SES-005: y ya no por el viejo");
}

#[test]
fn ses_006_una_sesion_invalidada_no_se_rota() {
    let a = almacen();
    let s = a.crear().unwrap();
    s.lock().unwrap().invalidar();
    assert!(a.rotar(&s).is_err(), "SES-006");
}

#[test]
fn ses_007_dos_peticiones_a_la_vez_no_se_pisan() {
    let a = Arc::new(almacen());
    let s = a.crear().unwrap();
    let hilos: Vec<_> = (0..16)
        .map(|i| {
            let s = Arc::clone(&s);
            std::thread::spawn(move || s.lock().unwrap().poner(&format!("k{i}"), "v").unwrap())
        })
        .collect();
    for h in hilos {
        h.join().unwrap();
    }
    let g = s.lock().unwrap();
    for i in 0..16 {
        assert!(g.leer(&format!("k{i}")).unwrap().is_some(), "SES-007: se perdió k{i}");
    }
}

#[test]
fn ses_008_y_011_la_cookie_se_emite_una_sola_vez() {
    let a = almacen();
    let s = a.crear().unwrap();
    let mut g = s.lock().unwrap();
    assert!(g.cookie_pendiente().is_some(), "SES-008: la respuesta que la crea la lleva");
    assert!(g.cookie_pendiente().is_none(), "SES-011: consultarla la consume");
}

#[test]
fn ses_009_la_cookie_declara_sus_banderas() {
    let sin_tls = sesion::cabecera_cookie("abc", false);
    assert!(sin_tls.contains("HttpOnly"), "SES-009");
    assert!(sin_tls.contains("SameSite=Lax"), "SES-009");
    assert!(!sin_tls.contains("Secure"), "SES-009: sin TLS no lleva Secure");
    assert!(sesion::cabecera_cookie("abc", true).contains("Secure"), "SES-009: con TLS sí");
}

#[test]
fn ses_010_la_cookie_no_depende_del_protocolo() {
    // El requisito nació de un fallo en Java: dos salidas, una por versión del protocolo, y solo
    // una preguntaba por la cookie. Aquí no puede repetirse, y no por disciplina: `cookie_pendiente`
    // toma `&mut self`, así que dos caminos de salida no pueden consultarla los dos sin que el
    // compilador lo señale. El contrato exige el comportamiento; el lenguaje decide si se puede
    // incumplir por descuido.
    let a = almacen();
    let s = a.crear().unwrap();
    let emitida = s.lock().unwrap().cookie_pendiente();
    assert!(emitida.is_some(), "SES-010");
    let id = emitida.unwrap();
    assert!(a.recuperar(Some(&id)).is_some(), "SES-010: se reconoce en la petición siguiente");
}

#[test]
fn caducidad_por_inactividad() {
    let a = Almacen::nuevo(Duration::from_millis(50), None);
    let id = a.crear().unwrap().lock().unwrap().id().to_string();
    assert!(a.recuperar(Some(&id)).is_some(), "antes de caducar sigue ahí");
    std::thread::sleep(Duration::from_millis(80));
    assert!(a.recuperar(Some(&id)).is_none(), "después de caducar ya no está");
    assert_eq!(a.cuantas(), 0, "y se suelta del almacén");
}

#[test]
fn caducidad_absoluta_aunque_se_toque() {
    // Solo con inactividad, una sesión tocada de vez en cuando vive para siempre. Es el hallazgo
    // de auditoría que en Java dio `sessionMaxLifetime`.
    let a = Almacen::nuevo(Duration::from_secs(600), Some(Duration::from_millis(50)));
    let s = a.crear().unwrap();
    let id = s.lock().unwrap().id().to_string();
    for _ in 0..5 {
        std::thread::sleep(Duration::from_millis(15));
        let _ = s.lock().unwrap().poner("toque", "sí");
    }
    assert!(a.recuperar(Some(&id)).is_none(), "la caducidad absoluta manda sobre los toques");
}

#[test]
fn lee_el_identificador_de_la_cabecera_cookie() {
    assert_eq!(sesion::id_de_cookie(Some("a=1; cero_sid=xyz; b=2")), Some("xyz"));
    assert_eq!(sesion::id_de_cookie(Some("a=1")), None);
    assert_eq!(sesion::id_de_cookie(None), None);
}
