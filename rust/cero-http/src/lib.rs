//! Cero en Rust — hito 1.
//!
//! Implementa el contrato de `spec/` para HTTP/1.1 y ruteo. No mira el código Java: mira los
//! requisitos numerados, y lo juzgan los mismos vectores de conformidad.

pub mod peticion;
pub mod ruta;
pub mod servidor;

pub use peticion::{Peticion, Rechazo};
pub use ruta::{Resolucion, Router};
pub use servidor::{Respuesta, Servidor};
