//! Servidor mínimo contra el que correr el banco de conformidad de `spec/banco/`.
//!
//! `/eco` **lee el cuerpo**: sin eso los vectores de encuadre no comprueban nada, porque un
//! servidor que responde sin leerlo pasa por válido sin haber parseado.
//!
//! La raíz responde algo más largo que «ok» porque h2spec necesita un cuerpo que no quepa en una
//! ventana apretada para poder medir el control de flujo. Con dos octetos se salta la prueba en
//! vez de fallarla, que es peor: parece que pasa.
//!
//!     cargo run --bin conforme 8777

use cero_http::{Respuesta, Router, Servidor};

fn main() -> std::io::Result<()> {
    let puerto: u16 = std::env::args().nth(1).and_then(|p| p.parse().ok()).unwrap_or(8777);
    let router = Router::nuevo()
        .ruta("GET", "/", "raiz").expect("patrón")
        .ruta("GET", "/eco", "eco").expect("patrón")
        .ruta("POST", "/eco", "eco").expect("patrón");
    Servidor::nuevo(router)
        .accion("raiz", |_| Respuesta::texto("cero, conforme\n"))
        .accion("eco", |c| Respuesta {
            cuerpo: c.peticion.cuerpo.clone(),
            ..Respuesta::texto("")
        })
        .escuchar(puerto)
}
