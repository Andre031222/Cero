//! Servir un directorio es la primera forma de abrir un agujero. Estas pruebas son el intento.

use cero_http::estaticos::Estaticos;

/// Un árbol **por prueba**, no uno compartido.
///
/// Con un solo directorio para todas, que corren en paralelo, `fs::write` trunca antes de
/// escribir: una leía `index.html` vacío justo mientras otra lo reescribía. Fallaba en Linux y no
/// en macOS, que es la peor clase de prueba —la que falla en la máquina de otro—, y lo que estaba
/// mal era esto y no el código que se prueba.
fn raiz(prueba: &str) -> std::path::PathBuf {
    let caja = std::env::temp_dir().join(format!("cero-est-{}-{prueba}", std::process::id()));
    let d = caja.join("raiz");
    std::fs::create_dir_all(d.join("sub")).unwrap();
    std::fs::write(d.join("index.html"), "<h1>portada</h1>").unwrap();
    std::fs::write(d.join("estilo.css"), "body{}").unwrap();
    std::fs::write(d.join("sub/hondo.txt"), "hondo").unwrap();
    // Justo fuera de la raíz, que es donde apunta el `..` que las pruebas intentan.
    std::fs::write(caja.join("cero-secreto.txt"), "no deberías ver esto").unwrap();
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
    // Una aplicación de una sola página resuelve sus rutas en el navegador: el servidor devuelve
    // la portada y deja que el cliente decida.
    let r = e.servir("/panel/usuarios/7");
    assert_eq!(r.estado, 200, "el respaldo atiende");
    assert!(String::from_utf8_lossy(&r.cuerpo).contains("portada"));
    // Con respaldo, **todo** devuelve 200 por diseño: esa es la razón de ser del respaldo. Lo
    // que no puede pasar es que salga contenido de fuera de la raíz, y eso es lo que se afirma.
    let intento = e.servir("/../cero-secreto.txt");
    assert!(!String::from_utf8_lossy(&intento.cuerpo).contains("no deberías"),
            "el respaldo no puede ser una forma de saltarse la raíz");
    assert!(String::from_utf8_lossy(&intento.cuerpo).contains("portada"),
            "lo que sale es el respaldo, no el secreto");
}

#[test]
fn lo_desconocido_no_se_adivina() {
    let d = raiz("desconocido");
    std::fs::write(d.join("raro.xyz"), "datos").unwrap();
    let e = Estaticos::en(d.to_str().unwrap()).unwrap();
    assert_eq!(e.servir("/raro.xyz").tipo, "application/octet-stream",
               "adivinar el tipo es lo que nosniff existe para impedir");
}
