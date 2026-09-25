//! Seguridad transversal, según `spec/seguridad.md` requisitos `SEG-001`–`SEG-028`.
//!
//! Lo que el framework hace por la aplicación sin que la aplicación lo pida. El contrato fija el
//! comportamiento **por defecto**, no la política: un framework cuyo defecto es inseguro traslada
//! al programador una decisión que ese programador no sabe que está tomando.

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

// ── Cabeceras de seguridad · SEG-001 a SEG-007 ──────────────────────────────────────────────

pub struct Cabeceras {
    /// SEG-007: aflojar el enmarcado tiene que ser posible sin tocar el resto.
    pub enmarcado: &'static str,
    /// SEG-006: sin CSP declarada no se inventa una. Una política adivinada rompe la aplicación
    /// y enseña a desactivarla, que es peor que no tenerla.
    pub csp: Option<String>,
}

impl Default for Cabeceras {
    fn default() -> Self {
        Cabeceras { enmarcado: "DENY", csp: None }
    }
}

impl Cabeceras {
    pub fn aplicar(&self, seguro: bool) -> Vec<(String, String)> {
        let mut h = vec![
            ("X-Content-Type-Options".into(), "nosniff".into()), // SEG-001
            ("X-Frame-Options".into(), self.enmarcado.into()),   // SEG-002
            ("Referrer-Policy".into(), "strict-origin-when-cross-origin".into()), // SEG-003
            ("Permissions-Policy".into(), "camera=(), microphone=(), geolocation=()".into()), // SEG-004
        ];
        // SEG-005: sin TLS no se manda HSTS. Prometer transporte seguro sobre texto plano es una
        // promesa que no se puede cumplir, y el navegador la recuerda.
        if seguro {
            h.push(("Strict-Transport-Security".into(), "max-age=31536000; includeSubDomains".into()));
        }
        if let Some(csp) = &self.csp {
            h.push(("Content-Security-Policy".into(), csp.clone()));
        }
        h
    }
}

// ── CORS · SEG-008 a SEG-014 ────────────────────────────────────────────────────────────────

pub enum Origenes {
    Cualquiera,
    Lista(Vec<String>),
}

pub struct Cors {
    pub origenes: Origenes,
    pub credenciales: bool,
    pub metodos: Vec<String>,
    pub cabeceras: Vec<String>,
    pub max_age: u32,
}

/// Qué hacer con una petición según CORS. El preflight y la petición simple se tratan distinto a
/// propósito: ver `SEG-010` y `SEG-013`.
pub enum Decision {
    /// Sigue su curso, con estas cabeceras añadidas.
    Sigue(Vec<(String, String)>),
    /// Responde ya, con este estado y estas cabeceras.
    Corta(u16, Vec<(String, String)>),
}

impl Cors {
    fn permitido(&self, origen: &str) -> bool {
        match &self.origenes {
            Origenes::Cualquiera => true,
            Origenes::Lista(l) => l.iter().any(|o| o == origen),
        }
    }

    fn cabecera_origen(&self, origen: &str) -> String {
        // SEG-014: con comodín y sin credenciales se responde comodín; con credenciales hay que
        // devolver el origen concreto, porque el navegador rechaza `*` junto a credenciales.
        match (&self.origenes, self.credenciales) {
            (Origenes::Cualquiera, false) => "*".into(),
            _ => origen.to_string(),
        }
    }

    pub fn decidir(&self, metodo: &str, origen: Option<&str>) -> Decision {
        // SEG-011: sin Origin no se añade ninguna cabecera CORS.
        let Some(origen) = origen else {
            return Decision::Sigue(Vec::new());
        };
        let preflight = metodo == "OPTIONS";
        if !self.permitido(origen) {
            // SEG-013: el preflight de origen ajeno se rechaza, porque su único propósito es
            // preguntar. SEG-010: la petición simple no se bloquea — ya llegó, y bloquearla daría
            // una falsa sensación de protección; quien decide es el navegador.
            return if preflight {
                Decision::Corta(403, Vec::new())
            } else {
                Decision::Sigue(vec![("Vary".into(), "Origin".into())])
            };
        }
        let mut h = vec![
            ("Access-Control-Allow-Origin".into(), self.cabecera_origen(origen)),
            // SEG-009: la respuesta depende del origen, así que las cachés tienen que saberlo.
            ("Vary".into(), "Origin".into()),
        ];
        if self.credenciales {
            h.push(("Access-Control-Allow-Credentials".into(), "true".into()));
        }
        if preflight {
            // SEG-012: el preflight admitido responde 204 anunciando qué se permite.
            h.push(("Access-Control-Allow-Methods".into(), self.metodos.join(", ")));
            h.push(("Access-Control-Allow-Headers".into(), self.cabeceras.join(", ")));
            h.push(("Access-Control-Max-Age".into(), self.max_age.to_string()));
            return Decision::Corta(204, h);
        }
        Decision::Sigue(h)
    }
}

// ── CSRF · SEG-015 a SEG-019 ────────────────────────────────────────────────────────────────

const SEGUROS: [&str; 4] = ["GET", "HEAD", "OPTIONS", "TRACE"];

/// SEG-019: la exención casa por **segmento completo**, nunca por prefijo pelado. Eximir
/// `/api/publico` no puede eximir `/api/publicoSECRETO`: ese fue un hallazgo de auditoría real.
pub fn exento(camino: &str, exenciones: &[String]) -> bool {
    exenciones.iter().any(|e| {
        let e = e.trim_end_matches('/');
        camino == e || camino.starts_with(&format!("{e}/"))
    })
}

pub fn csrf_valido(metodo: &str, camino: &str, exenciones: &[String],
                   token_sesion: Option<&str>, token_peticion: Option<&str>) -> bool {
    if SEGUROS.contains(&metodo) || exento(camino, exenciones) {
        return true; // SEG-015
    }
    // SEG-016 y SEG-018: sin token o con token erróneo, no pasa.
    match (token_sesion, token_peticion) {
        (Some(a), Some(b)) => constante(a, b),
        _ => false,
    }
}

/// Comparación en tiempo constante: con `==` el tiempo depende del prefijo común y filtra el
/// token carácter a carácter.
fn constante(a: &str, b: &str) -> bool {
    if a.len() != b.len() {
        return false;
    }
    a.bytes().zip(b.bytes()).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

// ── Límite de peticiones · SEG-020 a SEG-022 ────────────────────────────────────────────────

pub struct Limitador {
    cupo: u32,
    ventana: Duration,
    cuentas: Mutex<HashMap<String, (u32, Instant)>>,
}

pub struct Veredicto {
    pub permitida: bool,
    pub limite: u32,
    pub restante: u32,
    pub reintentar_en: u64,
}

impl Limitador {
    pub fn nuevo(cupo: u32, ventana: Duration) -> Limitador {
        Limitador { cupo, ventana, cuentas: Mutex::new(HashMap::new()) }
    }

    /// La clave es **solo** el cliente. SEG-022: meter la ruta dentro daba cuota nueva con solo
    /// cambiar de camino, y además hacía crecer el mapa sin tope con rutas inventadas: no era
    /// solo un límite esquivable, era agotamiento de memoria.
    pub fn pedir(&self, cliente: &str) -> Veredicto {
        let mut cuentas = self.cuentas.lock().expect("limitador envenenado");
        let ahora = Instant::now();
        let (usadas, desde) = cuentas.entry(cliente.to_string()).or_insert((0, ahora));
        if desde.elapsed() > self.ventana {
            *usadas = 0;
            *desde = ahora;
        }
        *usadas += 1;
        let usadas = *usadas;
        let desde = *desde;
        Veredicto {
            permitida: usadas <= self.cupo,
            limite: self.cupo,
            restante: self.cupo.saturating_sub(usadas),
            reintentar_en: self.ventana.saturating_sub(desde.elapsed()).as_secs() + 1,
        }
    }

    pub fn claves(&self) -> usize {
        self.cuentas.lock().map(|c| c.len()).unwrap_or(0)
    }
}

/// SEG-020 y SEG-021: el 429 lleva `Retry-After`, y toda respuesta anuncia límite y restante.
pub fn cabeceras_limite(v: &Veredicto) -> Vec<(String, String)> {
    let mut h = vec![
        ("X-RateLimit-Limit".into(), v.limite.to_string()),
        ("X-RateLimit-Remaining".into(), v.restante.to_string()),
    ];
    if !v.permitida {
        h.push(("Retry-After".into(), v.reintentar_en.to_string()));
    }
    h
}

// ── Saneado · SEG-023 a SEG-026 ─────────────────────────────────────────────────────────────

const PROHIBIDAS: [&str; 5] = ["script", "style", "iframe", "object", "embed"];

/// SEG-023 y SEG-024: quita lo ejecutable y **conserva el marcado inocuo**. Un saneador que borra
/// todo se desactiva, y entonces no sanea nada.
pub fn sanear_html(entrada: &str) -> String {
    let mut salida = String::with_capacity(entrada.len());
    let mut resto = entrada;
    while let Some(i) = resto.find('<') {
        salida.push_str(&resto[..i]);
        let Some(fin) = resto[i..].find('>') else { break };
        let etiqueta = &resto[i + 1..i + fin];
        let nombre: String = etiqueta
            .trim_start_matches('/')
            .chars()
            .take_while(|c| c.is_ascii_alphanumeric())
            .collect::<String>()
            .to_ascii_lowercase();
        if PROHIBIDAS.contains(&nombre.as_str()) {
            // Se descarta la etiqueta **y su contenido**: dejar el texto de un <script> fuera
            // basta para que otro contexto lo vuelva a ejecutar.
            let cierre = format!("</{nombre}>");
            resto = match resto[i..].to_ascii_lowercase().find(&cierre) {
                Some(c) => &resto[i + c + cierre.len()..],
                None => "",
            };
            continue;
        }
        salida.push_str(&limpiar_atributos(&resto[i..i + fin + 1]));
        resto = &resto[i + fin + 1..];
    }
    salida.push_str(resto);
    salida
}

fn limpiar_atributos(etiqueta: &str) -> String {
    let bajo = etiqueta.to_ascii_lowercase();
    // Manejadores de evento y el protocolo `javascript:`: las dos formas de ejecutar sin <script>.
    if bajo.contains(" on") && bajo.contains('=') {
        let corte = bajo.find(" on").unwrap();
        return format!("{}>", &etiqueta[..corte]);
    }
    if bajo.contains("javascript:") {
        let corte = bajo.find(|c| c == ' ').unwrap_or(etiqueta.len() - 1);
        return format!("{}>", &etiqueta[..corte]);
    }
    etiqueta.to_string()
}

/// SEG-025: a texto plano no queda ninguna etiqueta, y del contenido de `script` no queda rastro.
pub fn sanear_texto(entrada: &str) -> String {
    let html = sanear_html(entrada);
    let mut salida = String::with_capacity(html.len());
    let mut dentro = false;
    for c in html.chars() {
        match c {
            '<' => dentro = true,
            '>' => dentro = false,
            _ if !dentro => salida.push(c),
            _ => {}
        }
    }
    salida
}

/// SEG-026: quita rutas y separadores de los dos sistemas, y nunca devuelve vacío.
pub fn sanear_nombre(entrada: &str) -> String {
    let base = entrada.rsplit(['/', '\\']).next().unwrap_or("");
    let limpio: String = base
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || "._- ".contains(*c))
        .collect();
    let limpio = limpio.trim_matches(['.', ' ']).to_string();
    if limpio.is_empty() { "archivo".into() } else { limpio }
}
