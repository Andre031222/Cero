//! Cuando una acción no puede responder: `spec/ruteo.md`, `RUT-024` a `RUT-027`.
//!
//! Un fallo no es una respuesta con estado feo. La diferencia está en quién decide qué sale: una
//! respuesta la escribe la acción entera, y un fallo lo termina de escribir el framework, que es
//! el único sitio donde se puede garantizar que un error interno no se cuenta por ahí.

use crate::contexto::Respuesta;
use crate::json::Json;

/// Lo que una acción devuelve cuando no puede seguir.
///
/// `RUT-026`: un estado declarado se conserva con su mensaje, porque «el artículo no existe» es un
/// 404 que el cliente tiene que poder leer. `RUT-024`: un 5xx no, porque ahí el mensaje habla de
/// las tripas del servidor y quien lo provocó no tiene por qué verlas.
#[derive(Debug, Clone, PartialEq)]
pub struct Fallo {
    pub estado: u16,
    pub mensaje: String,
    /// El motivo cuando no cabe en una frase: `SEG-028` pide decir qué campo falló y por qué, y
    /// una lista de campos metida en una cadena obliga a quien la recibe a volver a partirla.
    pub detalle: Option<Json>,
}

impl Fallo {
    pub fn nuevo(estado: u16, mensaje: &str) -> Fallo {
        Fallo { estado, mensaje: mensaje.into(), detalle: None }
    }

    pub fn detallado(estado: u16, mensaje: &str, detalle: Json) -> Fallo {
        Fallo { detalle: Some(detalle), ..Fallo::nuevo(estado, mensaje) }
    }

    /// Lo que no se previó. El mensaje es para el log, no para la respuesta.
    pub fn interno(mensaje: &str) -> Fallo {
        Fallo::nuevo(500, mensaje)
    }

    /// Si el mensaje puede salir al cliente. La línea está en el 500 y no en una lista de estados
    /// permitidos: lo que no se previó es justo lo que no se sabe si es contable.
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

/// Lo que puede devolver una acción.
///
/// Existe para que una acción que no falla no tenga que decirlo. Sin esto, añadir fallos obligaba
/// a envolver en `Ok(...)` hasta la acción más tonta, y un framework que cobra ceremonia por una
/// función que nunca falla acaba teniendo acciones que se tragan sus errores para no pagarla.
///
/// Son **dos** implementaciones y no tres a propósito. Una tercera para `Result<Respuesta,
/// Respuesta>` haría ambiguo el tipo de error de cualquier acción que use `?`, y entonces habría
/// que anotar el retorno a mano en cada una: la ceremonia volvería por la puerta de atrás. Por eso
/// los ayudantes del contexto —`variable_como`, `cuerpo_json`— devuelven `Fallo` y no una
/// respuesta ya hecha.
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
