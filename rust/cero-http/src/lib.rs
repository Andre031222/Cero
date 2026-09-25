//! Cero en Rust — hito 1.
//!
//! Implementa el contrato de `spec/` para HTTP/1.1 y ruteo. No mira el código Java: mira los
//! requisitos numerados, y lo juzgan los mismos vectores de conformidad.

pub mod contexto;
pub mod estaticos;
pub mod json;
pub mod observabilidad;
pub mod peticion;
pub mod sesion;
pub mod ruta;
pub mod seguridad;
pub mod servidor;

pub use peticion::{Peticion, Rechazo};
pub use ruta::{Resolucion, Router};
pub use sesion::{Almacen, Sesion};
pub use contexto::{Contexto, Respuesta};
pub use json::Json;
pub use servidor::Servidor;
