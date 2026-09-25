//! HTTP/2, según `spec/http2.md`.
//!
//! Se implementa por capas y en el orden en que el contrato se puede comprobar: primero las
//! tramas, que es donde viven casi todos los rechazos, y luego HPACK y los flujos.

pub mod trama;

pub use trama::{Ajustes, Error, FalloConexion, Trama, PREAMBULO};
