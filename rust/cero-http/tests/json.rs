//! JSON: lo normal, los bordes y lo hostil.

use cero_http::json::{leer, Json};

#[test]
fn escribe_los_tipos_basicos() {
    assert_eq!(Json::Nulo.escribir(), "null");
    assert_eq!(Json::Bool(true).escribir(), "true");
    assert_eq!(Json::Numero(42.0).escribir(), "42", "un entero no sale como 42.0");
    assert_eq!(Json::Numero(1.5).escribir(), "1.5");
    assert_eq!(Json::from("hola").escribir(), "\"hola\"");
}

#[test]
fn las_claves_salen_en_orden_estable() {
    let a = Json::objeto(vec![("z", 1i64.into()), ("a", 2i64.into())]);
    let b = Json::objeto(vec![("a", 2i64.into()), ("z", 1i64.into())]);
    assert_eq!(a.escribir(), b.escribir(), "mismo objeto, mismos bytes");
    assert_eq!(a.escribir(), "{\"a\":2,\"z\":1}");
}

#[test]
fn escapa_lo_que_rompe_una_pagina() {
    // Un JSON incrustado en HTML que contenga `</script` cierra la etiqueta: es XSS a través de
    // una respuesta perfectamente válida.
    let s = Json::from("</script><img onerror=x>").escribir();
    assert!(!s.contains("</script"), "SEG: {s}");
    assert!(!s.contains('<') && !s.contains('>'), "ni < ni > sin escapar · {s}");
}

#[test]
fn escapa_los_controles() {
    let s = Json::from("a\u{0}b\nc").escribir();
    assert!(s.contains("\\u0000"), "el nulo va escapado · {s}");
    assert!(s.contains("\\n"), "{s}");
}

#[test]
fn nan_e_infinito_no_producen_un_documento_ilegible() {
    assert_eq!(Json::Numero(f64::NAN).escribir(), "null");
    assert_eq!(Json::Numero(f64::INFINITY).escribir(), "null");
}

#[test]
fn lee_lo_que_escribe() {
    let v = Json::objeto(vec![
        ("id", 7i64.into()),
        ("nombre", "andré".into()),
        ("activo", true.into()),
        ("notas", Json::lista(vec![1i64.into(), 2i64.into()])),
        ("sin", Json::Nulo),
    ]);
    let ida = v.escribir();
    let vuelta = leer(&ida).expect("se relee");
    assert_eq!(v, vuelta, "ida y vuelta conservan el valor");
    assert_eq!(vuelta.escribir(), ida, "y los bytes");
}

#[test]
fn entra_por_un_camino_del_arbol() {
    let v = leer(r#"{"usuario":{"direccion":{"calle":"Puno"}},"items":[{"id":9}]}"#).unwrap();
    assert_eq!(v.ruta("usuario.direccion.calle").and_then(Json::texto), Some("Puno"));
    assert_eq!(v.ruta("items.0.id").and_then(Json::entero), Some(9));
    assert!(v.ruta("usuario.no.existe").is_none());
}

#[test]
fn lee_escapes_unicode() {
    let v = leer(r#"{"x":"éA"}"#).unwrap();
    assert_eq!(v.get("x").and_then(Json::texto), Some("éA"));
}

#[test]
fn rechaza_lo_que_no_es_json() {
    for malo in [
        "",
        "{",
        "{\"a\"}",
        "{\"a\":}",
        "[1,]",
        "{'a':1}",
        "tru",
        "{\"a\":1} sobra",
    ] {
        assert!(leer(malo).is_err(), "debería rechazar: {malo:?}");
    }
}

#[test]
fn rechaza_un_control_sin_escapar_dentro_de_una_cadena() {
    assert!(leer("{\"a\":\"x\u{1}y\"}").is_err(), "RFC 8259 §7");
}

#[test]
fn un_documento_muy_anidado_no_desborda_la_pila() {
    // 5 000 corchetes: con recursión sin tope, esto revienta el proceso. Es denegación de
    // servicio con un cuerpo de 10 KB.
    let hondo = format!("{}{}", "[".repeat(5_000), "]".repeat(5_000));
    assert!(leer(&hondo).is_err(), "tiene que rechazarse, no colgarse");
}

#[test]
fn los_numeros_grandes_y_negativos() {
    assert_eq!(leer("-17").unwrap().entero(), Some(-17));
    assert_eq!(leer("1e3").unwrap().numero(), Some(1000.0));
    assert_eq!(leer("0.5").unwrap().numero(), Some(0.5));
}
