use std::io::ErrorKind;

use cero_http::{Json, Peticion, Respuesta, Router, Servidor};

fn peticion(metodo: &str, destino: &str, cuerpo: &str) -> Peticion {
    Peticion {
        metodo: metodo.into(),
        destino: destino.into(),
        version: "HTTP/1.1".into(),
        cabeceras: [("host".to_string(), "x".to_string())].into_iter().collect(),
        cuerpo: cuerpo.as_bytes().to_vec(),
    }
}

fn pedir(s: &Servidor, metodo: &str, destino: &str, cuerpo: &str) -> (u16, String) {
    let r = s.responder(&peticion(metodo, destino, cuerpo), "x");
    (r.estado, String::from_utf8_lossy(&r.cuerpo).into_owned())
}

fn ejemplo() -> Servidor {
    Servidor::nuevo_vacio()
        .get("/", |_| Respuesta::json(Json::objeto(vec![("hola", "mundo".into())])))
        .get("/saludo/{nombre}", |c| {
            let nombre = c.variable("nombre").unwrap_or("mundo");
            Respuesta::json(Json::objeto(vec![("saludo", format!("hola, {nombre}").into())]))
        })
        .post("/eco", |c| Ok(Respuesta::json(c.cuerpo_json()?)))
}

#[test]
fn una_llamada_registra_la_ruta_y_la_accion() {
    let s = ejemplo();
    assert_eq!(pedir(&s, "GET", "/", ""), (200, r#"{"hola":"mundo"}"#.into()));
    assert_eq!(pedir(&s, "GET", "/saludo/ana", ""), (200, r#"{"saludo":"hola, ana"}"#.into()));
    assert_eq!(pedir(&s, "POST", "/eco", r#"{"a":1}"#), (200, r#"{"a":1}"#.into()));
    assert_eq!(pedir(&s, "POST", "/eco", "no es json").0, 400);
    assert_eq!(pedir(&s, "DELETE", "/eco", "").0, 405);
}

#[test]
fn los_cinco_verbos() {
    let s = Servidor::nuevo_vacio()
        .get("/v", |_| Respuesta::texto("get"))
        .post("/v", |_| Respuesta::texto("post"))
        .put("/v", |_| Respuesta::texto("put"))
        .delete("/v", |_| Respuesta::texto("delete"))
        .patch("/v", |_| Respuesta::texto("patch"));
    for verbo in ["GET", "POST", "PUT", "DELETE", "PATCH"] {
        assert_eq!(pedir(&s, verbo, "/v", ""), (200, verbo.to_ascii_lowercase()));
    }
}

#[test]
fn convive_con_ruta_y_accion() {
    let router = Router::nuevo().ruta("GET", "/larga", "larga").unwrap();
    let s = Servidor::nuevo(router)
        .accion("larga", |_| Respuesta::texto("larga"))
        .get("/corta", |_| Respuesta::texto("corta"));
    assert_eq!(pedir(&s, "GET", "/larga", ""), (200, "larga".into()));
    assert_eq!(pedir(&s, "GET", "/corta", ""), (200, "corta".into()));
}

#[test]
fn un_patron_mal_formado_hace_fallar_escuchar() {
    let error = Servidor::nuevo_vacio()
        .get("/bien", |_| Respuesta::texto("bien"))
        .get("/mal/{abierta", |_| Respuesta::texto("mal"))
        .direccion("127.0.0.1")
        .escuchar(0)
        .unwrap_err();
    assert_eq!(error.kind(), ErrorKind::InvalidInput);
    assert!(error.to_string().contains("/mal/{abierta"), "{error}");
}

#[test]
fn el_patron_mal_formado_no_rompe_las_demas_rutas() {
    let s = Servidor::nuevo_vacio()
        .get("/antes", |_| Respuesta::texto("antes"))
        .get("/mal/{abierta", |_| Respuesta::texto("mal"))
        .get("/despues", |_| Respuesta::texto("despues"));
    assert_eq!(pedir(&s, "GET", "/antes", ""), (200, "antes".into()));
    assert_eq!(pedir(&s, "GET", "/despues", ""), (200, "despues".into()));
}
