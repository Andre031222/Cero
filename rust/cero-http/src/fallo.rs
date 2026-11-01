use crate::contexto::Respuesta;
use crate::json::Json;

#[derive(Debug, Clone, PartialEq)]
pub struct Fallo {
    pub estado: u16,
    pub mensaje: String,
    pub detalle: Option<Json>,
}

impl Fallo {
    pub fn nuevo(estado: u16, mensaje: &str) -> Fallo {
        Fallo { estado, mensaje: mensaje.into(), detalle: None }
    }

    pub fn detallado(estado: u16, mensaje: &str, detalle: Json) -> Fallo {
        Fallo { detalle: Some(detalle), ..Fallo::nuevo(estado, mensaje) }
    }

    pub fn interno(mensaje: &str) -> Fallo {
        Fallo::nuevo(500, mensaje)
    }

    pub fn contable(&self) -> bool {
        self.estado < 500
    }
}

impl std::fmt::Display for Fallo {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        write!(f, "{} ({})", self.mensaje, self.estado)
    }
}

impl std::error::Error for Fallo {}

pub trait EnRespuesta {
    fn en_respuesta(self) -> Result<Respuesta, Fallo>;
}

impl EnRespuesta for Respuesta {
    fn en_respuesta(self) -> Result<Respuesta, Fallo> {
        Ok(self)
    }
}

impl EnRespuesta for Result<Respuesta, Fallo> {
    fn en_respuesta(self) -> Result<Respuesta, Fallo> {
        self
    }
}
