//! JSON: leer y escribir, sin dependencias.
//!
//! Java tiene el suyo en `cero-core` y por el mismo motivo: un framework que obliga a traer una
//! biblioteca de JSON para devolver un objeto no tiene cero dependencias, tiene una escondida.
//!
//! No hay derivación automática —eso pide macros procedurales, que en Rust son una caja externa—,
//! así que la aplicación construye el valor. A cambio, el árbol es explícito y no hay reflexión
//! en el camino caliente, que es lo mismo que Java persigue resolviendo la vinculación al
//! registrar la ruta.

use std::collections::BTreeMap;
use std::fmt::Write as _;

#[derive(Debug, Clone, PartialEq)]
pub enum Json {
    Nulo,
    Bool(bool),
    Numero(f64),
    Texto(String),
    Lista(Vec<Json>),
    /// `BTreeMap` y no `HashMap`: el orden de las claves es estable entre ejecuciones, así que
    /// dos respuestas iguales producen bytes iguales y se pueden comparar y cachear.
    Objeto(BTreeMap<String, Json>),
}

impl Json {
    pub fn objeto(pares: Vec<(&str, Json)>) -> Json {
        Json::Objeto(pares.into_iter().map(|(k, v)| (k.to_string(), v)).collect())
    }

    pub fn lista(items: Vec<Json>) -> Json {
        Json::Lista(items)
    }

    pub fn get(&self, clave: &str) -> Option<&Json> {
        match self {
            Json::Objeto(m) => m.get(clave),
            _ => None,
        }
    }

    /// Un punto del árbol: `"usuario.direccion.calle"`, o `"items.0.id"` para entrar en listas.
    pub fn ruta(&self, camino: &str) -> Option<&Json> {
        let mut actual = self;
        for paso in camino.split('.') {
            actual = match actual {
                Json::Objeto(m) => m.get(paso)?,
                Json::Lista(l) => l.get(paso.parse::<usize>().ok()?)?,
                _ => return None,
            };
        }
        Some(actual)
    }

    pub fn texto(&self) -> Option<&str> {
        match self {
            Json::Texto(s) => Some(s),
            _ => None,
        }
    }

    pub fn numero(&self) -> Option<f64> {
        match self {
            Json::Numero(n) => Some(*n),
            _ => None,
        }
    }

    pub fn entero(&self) -> Option<i64> {
        match self {
            Json::Numero(n) if n.fract() == 0.0 => Some(*n as i64),
            _ => None,
        }
    }

    pub fn bool(&self) -> Option<bool> {
        match self {
            Json::Bool(b) => Some(*b),
            _ => None,
        }
    }

    pub fn items(&self) -> Option<&[Json]> {
        match self {
            Json::Lista(l) => Some(l),
            _ => None,
        }
    }

    pub fn escribir(&self) -> String {
        let mut s = String::new();
        self.volcar(&mut s);
        s
    }

    fn volcar(&self, s: &mut String) {
        match self {
            Json::Nulo => s.push_str("null"),
            Json::Bool(b) => s.push_str(if *b { "true" } else { "false" }),
            Json::Numero(n) => {
                // NaN e infinito no existen en JSON. Escribirlos produce un documento que nadie
                // puede leer, así que se emite null, que sí es válido.
                if n.is_finite() {
                    if n.fract() == 0.0 && n.abs() < 1e15 {
                        let _ = write!(s, "{}", *n as i64);
                    } else {
                        let _ = write!(s, "{n}");
                    }
                } else {
                    s.push_str("null");
                }
            }
            Json::Texto(t) => escapar(t, s),
            Json::Lista(l) => {
                s.push('[');
                for (i, v) in l.iter().enumerate() {
                    if i > 0 {
                        s.push(',');
                    }
                    v.volcar(s);
                }
                s.push(']');
            }
            Json::Objeto(m) => {
                s.push('{');
                for (i, (k, v)) in m.iter().enumerate() {
                    if i > 0 {
                        s.push(',');
                    }
                    escapar(k, s);
                    s.push(':');
                    v.volcar(s);
                }
                s.push('}');
            }
        }
    }
}

impl From<&str> for Json { fn from(v: &str) -> Json { Json::Texto(v.into()) } }
impl From<String> for Json { fn from(v: String) -> Json { Json::Texto(v) } }
impl From<i64> for Json { fn from(v: i64) -> Json { Json::Numero(v as f64) } }
impl From<u32> for Json { fn from(v: u32) -> Json { Json::Numero(v as f64) } }
impl From<f64> for Json { fn from(v: f64) -> Json { Json::Numero(v) } }
impl From<bool> for Json { fn from(v: bool) -> Json { Json::Bool(v) } }
impl<T: Into<Json>> From<Option<T>> for Json {
    fn from(v: Option<T>) -> Json {
        v.map_or(Json::Nulo, Into::into)
    }
}

/// Escapa lo que el RFC 8259 exige, incluidos los controles por debajo de 0x20.
///
/// `</script` también se escapa: un JSON incrustado en una página que contenga esa secuencia
/// cierra la etiqueta y lo que siga se ejecuta. Es XSS a través de una respuesta válida.
fn escapar(t: &str, s: &mut String) {
    s.push('"');
    for c in t.chars() {
        match c {
            '"' => s.push_str("\\\""),
            '\\' => s.push_str("\\\\"),
            '\n' => s.push_str("\\n"),
            '\r' => s.push_str("\\r"),
            '\t' => s.push_str("\\t"),
            '<' => s.push_str("\\u003c"),
            '>' => s.push_str("\\u003e"),
            '&' => s.push_str("\\u0026"),
            c if (c as u32) < 0x20 => {
                let _ = write!(s, "\\u{:04x}", c as u32);
            }
            c => s.push(c),
        }
    }
    s.push('"');
}

// ── Lectura ─────────────────────────────────────────────────────────────────────────────────

const PROFUNDIDAD_MAXIMA: usize = 64;

#[derive(Debug, PartialEq)]
pub struct ErrorJson(pub String);

pub fn leer(entrada: &str) -> Result<Json, ErrorJson> {
    let bytes: Vec<char> = entrada.chars().collect();
    let mut p = Lector { c: &bytes, i: 0, hondo: 0 };
    p.espacios();
    let v = p.valor()?;
    p.espacios();
    if p.i != p.c.len() {
        return Err(ErrorJson(format!("sobra texto en la posición {}", p.i)));
    }
    Ok(v)
}

struct Lector<'c> {
    c: &'c [char],
    i: usize,
    hondo: usize,
}

impl Lector<'_> {
    fn espacios(&mut self) {
        while self.i < self.c.len() && self.c[self.i].is_ascii_whitespace() {
            self.i += 1;
        }
    }

    fn mirar(&self) -> Option<char> {
        self.c.get(self.i).copied()
    }

    fn valor(&mut self) -> Result<Json, ErrorJson> {
        // Un documento muy anidado desborda la pila antes de terminar de leerse: es una denegación
        // de servicio con un cuerpo pequeño, y por eso hay tope.
        if self.hondo > PROFUNDIDAD_MAXIMA {
            return Err(ErrorJson("demasiado anidamiento".into()));
        }
        match self.mirar() {
            None => Err(ErrorJson("documento vacío".into())),
            Some('{') => self.objeto(),
            Some('[') => self.lista(),
            Some('"') => Ok(Json::Texto(self.cadena()?)),
            Some('t') => self.literal("true", Json::Bool(true)),
            Some('f') => self.literal("false", Json::Bool(false)),
            Some('n') => self.literal("null", Json::Nulo),
            Some(_) => self.numero(),
        }
    }

    fn literal(&mut self, texto: &str, v: Json) -> Result<Json, ErrorJson> {
        if self.c[self.i..].starts_with(&texto.chars().collect::<Vec<_>>()[..]) {
            self.i += texto.len();
            Ok(v)
        } else {
            Err(ErrorJson(format!("esperaba {texto} en la posición {}", self.i)))
        }
    }

    fn objeto(&mut self) -> Result<Json, ErrorJson> {
        self.i += 1;
        self.hondo += 1;
        let mut m = BTreeMap::new();
        self.espacios();
        if self.mirar() == Some('}') {
            self.i += 1;
            self.hondo -= 1;
            return Ok(Json::Objeto(m));
        }
        loop {
            self.espacios();
            let clave = self.cadena()?;
            self.espacios();
            if self.mirar() != Some(':') {
                return Err(ErrorJson(format!("esperaba ':' en la posición {}", self.i)));
            }
            self.i += 1;
            self.espacios();
            m.insert(clave, self.valor()?);
            self.espacios();
            match self.mirar() {
                Some(',') => self.i += 1,
                Some('}') => {
                    self.i += 1;
                    self.hondo -= 1;
                    return Ok(Json::Objeto(m));
                }
                _ => return Err(ErrorJson(format!("esperaba ',' o '}}' en {}", self.i))),
            }
        }
    }

    fn lista(&mut self) -> Result<Json, ErrorJson> {
        self.i += 1;
        self.hondo += 1;
        let mut l = Vec::new();
        self.espacios();
        if self.mirar() == Some(']') {
            self.i += 1;
            self.hondo -= 1;
            return Ok(Json::Lista(l));
        }
        loop {
            self.espacios();
            l.push(self.valor()?);
            self.espacios();
            match self.mirar() {
                Some(',') => self.i += 1,
                Some(']') => {
                    self.i += 1;
                    self.hondo -= 1;
                    return Ok(Json::Lista(l));
                }
                _ => return Err(ErrorJson(format!("esperaba ',' o ']' en {}", self.i))),
            }
        }
    }

    fn cadena(&mut self) -> Result<String, ErrorJson> {
        if self.mirar() != Some('"') {
            return Err(ErrorJson(format!("esperaba una cadena en {}", self.i)));
        }
        self.i += 1;
        let mut s = String::new();
        loop {
            let Some(c) = self.mirar() else {
                return Err(ErrorJson("cadena sin cerrar".into()));
            };
            self.i += 1;
            match c {
                '"' => return Ok(s),
                '\\' => {
                    let Some(e) = self.mirar() else {
                        return Err(ErrorJson("escape sin cerrar".into()));
                    };
                    self.i += 1;
                    match e {
                        '"' => s.push('"'),
                        '\\' => s.push('\\'),
                        '/' => s.push('/'),
                        'b' => s.push('\u{08}'),
                        'f' => s.push('\u{0c}'),
                        'n' => s.push('\n'),
                        'r' => s.push('\r'),
                        't' => s.push('\t'),
                        'u' => {
                            let hex: String = self.c.get(self.i..self.i + 4)
                                .ok_or_else(|| ErrorJson("\\u incompleto".into()))?
                                .iter().collect();
                            self.i += 4;
                            let n = u32::from_str_radix(&hex, 16)
                                .map_err(|_| ErrorJson(format!("\\u{hex} no es hexadecimal")))?;
                            s.push(char::from_u32(n).unwrap_or('\u{fffd}'));
                        }
                        otro => return Err(ErrorJson(format!("escape desconocido: \\{otro}"))),
                    }
                }
                // Los controles sin escapar no son JSON válido (RFC 8259 §7).
                c if (c as u32) < 0x20 => {
                    return Err(ErrorJson("control sin escapar dentro de una cadena".into()))
                }
                c => s.push(c),
            }
        }
    }

    fn numero(&mut self) -> Result<Json, ErrorJson> {
        let desde = self.i;
        if self.mirar() == Some('-') {
            self.i += 1;
        }
        while matches!(self.mirar(), Some(c) if c.is_ascii_digit() || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-')
        {
            self.i += 1;
        }
        let texto: String = self.c[desde..self.i].iter().collect();
        texto.parse::<f64>().map(Json::Numero).map_err(|_| ErrorJson(format!("número inválido: {texto}")))
    }
}
