//! Cero en Rust.
//!
//! Implementa el contrato de `spec/`. No mira el código Java: mira los requisitos numerados, y lo
//! juzgan los mismos vectores de conformidad, que son bytes sobre un socket y no saben en qué
//! lenguaje está escrito quien responde.

pub mod contexto;
pub mod estaticos;
pub mod fallo;
pub mod http2;
pub mod json;
pub mod observabilidad;
pub mod peticion;
pub mod registro;
pub mod ruta;
pub mod seguridad;
pub mod servidor;
pub mod sesion;
pub mod validacion;

pub use contexto::{Contexto, Respuesta};
pub use fallo::{EnRespuesta, Fallo};
pub use json::Json;
pub use peticion::{Peticion, Rechazo};
pub use registro::Registro;
pub use ruta::{Resolucion, Router};
pub use servidor::{Servidor, Siguiente};
pub use sesion::{Almacen, Sesion, Sesiones};
pub use validacion::{validar, Regla};
