use std::fs;
use std::path::PathBuf;
use std::{env, process};

use cero_http::estaticos::Estaticos;

fn raiz(prueba: &str) -> PathBuf {
    let caja = env::temp_dir().join(format!("cero-est-{}-{prueba}", process::id()));
    let d = caja.join("raiz");
    fs::create_dir_all(d.join("sub")).unwrap();
    fs::write(d.join("index.html"), "<h1>portada</h1>").unwrap();
    fs::write(d.join("estilo.css"), "body{}").unwrap();
    fs::write(d.join("sub/hondo.txt"), "hondo").unwrap();
    fs::write(caja.join("cero-secreto.txt"), "no deberías ver esto").unwrap();
    d
}

fn estaticos(prueba: &str) -> Estaticos {
    Estaticos::en(raiz(prueba).to_str().unwrap()).unwrap()
}

#[test]
fn sirve_lo_que_hay_con_su_tipo() {
    let e = estaticos("sirve");
    let r = e.servir("/index.html");
    assert_eq!(r.estado, 200);
    assert!(r.tipo.starts_with("text/html"), "{}", r.tipo);
    assert!(String::from_utf8_lossy(&r.cuerpo).contains("portada"));

    assert!(e.servir("/estilo.css").tipo.starts_with("text/css"));
    assert_eq!(e.servir("/sub/hondo.txt").estado, 200, "los subdirectorios también");
}

#[test]
fn lo_que_no_esta_da_404() {
    assert_eq!(estaticos("no-esta").servir("/no-existe.txt").estado, 404);
}

#[test]
fn no_se_puede_salir_de_la_raiz() {
    let e = estaticos("salir");
    for intento in [
        "/../cero-secreto.txt",
        "/../../etc/passwd",
        "/sub/../../cero-secreto.txt",
        "//etc/passwd",
        "/./../../cero-secreto.txt",
    ] {
        let r = e.servir(intento);
        assert_ne!(r.estado, 200, "se escapó con {intento:?}");
        assert!(!String::from_utf8_lossy(&r.cuerpo).contains("no deberías"), "{intento:?}");
    }
}

#[test]
fn el_respaldo_atiende_las_rutas_de_cliente() {
    let e = estaticos("respaldo").con_respaldo("index.html");
    let r = e.servir("/panel/usuarios/7");
    assert_eq!(r.estado, 200, "el respaldo atiende");
    assert!(String::from_utf8_lossy(&r.cuerpo).contains("portada"));
    let intento = e.servir("/../cero-secreto.txt");
    assert!(!String::from_utf8_lossy(&intento.cuerpo).contains("no deberías"),
            "el respaldo no puede ser una forma de saltarse la raíz");
    assert!(String::from_utf8_lossy(&intento.cuerpo).contains("portada"),
            "lo que sale es el respaldo, no el secreto");
}

#[test]
fn lo_desconocido_no_se_adivina() {
    let d = raiz("desconocido");
    fs::write(d.join("raro.xyz"), "datos").unwrap();
    let e = Estaticos::en(d.to_str().unwrap()).unwrap();
    assert_eq!(e.servir("/raro.xyz").tipo, "application/octet-stream",
               "adivinar el tipo es lo que nosniff existe para impedir");
}
