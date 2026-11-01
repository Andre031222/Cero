use crate::fallo::Fallo;
use crate::json::Json;

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
    let detalle = Json::objeto(vec![
        ("error", Json::Texto("el cuerpo no es válido".into())),
        ("campos", Json::Objeto(fallos.into_iter().collect())),
    ]);
    Err(Fallo::detallado(422, "el cuerpo no es válido", detalle))
}
