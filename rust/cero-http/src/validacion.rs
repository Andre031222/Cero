//! Validación del cuerpo en la ruta: `spec/seguridad.md`, `SEG-027` y `SEG-028`.
//!
//! **422 y no 400**, que es la única decisión de fondo del bloque: el cuerpo se entendió, lo que
//! falla es su contenido. Un cliente que distingue los dos casos puede reintentar en uno y no en
//! el otro; uno que recibe 400 para todo no sabe si insistir.
//!
//! Y el detalle por campo no es cortesía. Un 422 que solo dice «inválido» obliga a quien llama a
//! adivinar, y lo que hace en la práctica es reintentar con lo mismo.

use crate::fallo::Fallo;
use crate::json::Json;

/// Lo que se le exige a un campo. Se comprueba en este orden y se para en el primero que falla:
/// decir que un campo ausente además es corto no ayuda a nadie.
pub enum Regla {
    Obligatorio,
    Texto { minimo: usize, maximo: usize },
    Entero { minimo: i64, maximo: i64 },
}

impl Regla {
    fn falta(&self, valor: Option<&Json>) -> Option<String> {
        let Some(v) = valor else {
            return matches!(self, Regla::Obligatorio).then(|| "es obligatorio".to_string());
        };
        match self {
            // Presente y vacío es lo mismo que ausente para un campo obligatorio: un nombre de
            // cero caracteres no es un nombre, y aceptarlo mueve el problema a la base de datos.
            Regla::Obligatorio => (v.texto() == Some("")).then(|| "es obligatorio".to_string()),
            Regla::Texto { minimo, maximo } => match v.texto() {
                None => Some("tiene que ser texto".into()),
                Some(t) if t.chars().count() < *minimo => Some(format!("no llega a {minimo} caracteres")),
                Some(t) if t.chars().count() > *maximo => Some(format!("pasa de {maximo} caracteres")),
                Some(_) => None,
            },
            Regla::Entero { minimo, maximo } => match v.entero() {
                None => Some("tiene que ser un número entero".into()),
                Some(n) if n < *minimo => Some(format!("es menor que {minimo}")),
                Some(n) if n > *maximo => Some(format!("es mayor que {maximo}")),
                Some(_) => None,
            },
        }
    }
}

/// Comprueba el cuerpo y lo devuelve **tal cual** si pasa.
///
/// `SEG-027`: validar no es normalizar. Devolver algo retocado —un texto recortado, un número
/// redondeado— convierte la validación en una transformación silenciosa, y entonces lo que la
/// acción recibe ya no es lo que el cliente mandó.
pub fn validar(cuerpo: Json, reglas: &[(&str, Regla)]) -> Result<Json, Fallo> {
    let fallos: Vec<(String, Json)> = reglas
        .iter()
        .filter_map(|(campo, regla)| {
            regla.falta(cuerpo.get(campo)).map(|q| (campo.to_string(), Json::Texto(q)))
        })
        .collect();
    if fallos.is_empty() {
        return Ok(cuerpo);
    }
    // `SEG-028`: qué campo y por qué, no «inválido».
    let detalle = Json::objeto(vec![
        ("error", Json::Texto("el cuerpo no es válido".into())),
        ("campos", Json::Objeto(fallos.into_iter().collect())),
    ]);
    Err(Fallo::detallado(422, "el cuerpo no es válido", detalle))
}
