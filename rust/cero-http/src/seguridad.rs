use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

pub struct Cabeceras {
    pub enmarcado: &'static str,
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
            ("X-Content-Type-Options".into(), "nosniff".into()),
            ("X-Frame-Options".into(), self.enmarcado.into()),
            ("Referrer-Policy".into(), "strict-origin-when-cross-origin".into()),
            ("Permissions-Policy".into(), "camera=(), microphone=(), geolocation=()".into()),
        ];
        if seguro {
            h.push(("Strict-Transport-Security".into(), "max-age=31536000; includeSubDomains".into()));
        }
        if let Some(csp) = &self.csp {
            h.push(("Content-Security-Policy".into(), csp.clone()));
        }
        h
    }
}

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

pub enum Decision {
    Sigue(Vec<(String, String)>),
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
        match (&self.origenes, self.credenciales) {
            (Origenes::Cualquiera, false) => "*".into(),
            _ => origen.into(),
        }
    }

    pub fn decidir(&self, metodo: &str, origen: Option<&str>) -> Decision {
        let Some(origen) = origen else {
            return Decision::Sigue(Vec::new());
        };
        let preflight = metodo == "OPTIONS";
        if !self.permitido(origen) {
            return if preflight {
                Decision::Corta(403, Vec::new())
            } else {
                Decision::Sigue(vec![("Vary".into(), "Origin".into())])
            };
        }
        let mut h = vec![
            ("Access-Control-Allow-Origin".into(), self.cabecera_origen(origen)),
            ("Vary".into(), "Origin".into()),
        ];
        if self.credenciales {
            h.push(("Access-Control-Allow-Credentials".into(), "true".into()));
        }
        if preflight {
            h.push(("Access-Control-Allow-Methods".into(), self.metodos.join(", ")));
            h.push(("Access-Control-Allow-Headers".into(), self.cabeceras.join(", ")));
            h.push(("Access-Control-Max-Age".into(), self.max_age.to_string()));
            return Decision::Corta(204, h);
        }
        Decision::Sigue(h)
    }
}

const SEGUROS: [&str; 4] = ["GET", "HEAD", "OPTIONS", "TRACE"];

pub fn exento(camino: &str, exenciones: &[String]) -> bool {
    exenciones.iter().any(|e| {
        let e = e.trim_end_matches('/');
        camino == e || camino.starts_with(&format!("{e}/"))
    })
}

pub const CLAVE_CSRF: &str = "csrf";
pub const CAMPO_CSRF: &str = "_csrf";

pub fn csrf_valido(metodo: &str, camino: &str, exenciones: &[String],
                   token_sesion: Option<&str>, token_peticion: Option<&str>) -> bool {
    if SEGUROS.contains(&metodo) || exento(camino, exenciones) {
        return true;
    }
    match (token_sesion, token_peticion) {
        (Some(a), Some(b)) => constante(a, b),
        _ => false,
    }
}

fn constante(a: &str, b: &str) -> bool {
    if a.len() != b.len() {
        return false;
    }
    a.bytes().zip(b.bytes()).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

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

    pub fn pedir(&self, cliente: &str) -> Veredicto {
        let mut cuentas = self.cuentas.lock().expect("limitador envenenado");
        let ahora = Instant::now();
        let (usadas, desde) = cuentas.entry(cliente.into()).or_insert((0, ahora));
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
        self.cuentas.lock().map_or(0, |c| c.len())
    }
}

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

const PROHIBIDAS: [&str; 5] = ["script", "style", "iframe", "object", "embed"];

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
            .take_while(char::is_ascii_alphanumeric)
            .map(|c| c.to_ascii_lowercase())
            .collect();
        if PROHIBIDAS.contains(&nombre.as_str()) {
            let cierre = format!("</{nombre}>");
            let hasta = resto[i..].to_ascii_lowercase().find(&cierre);
            resto = hasta.map_or("", |c| &resto[i + c + cierre.len()..]);
            continue;
        }
        salida.push_str(&limpiar_atributos(&resto[i..=i + fin]));
        resto = &resto[i + fin + 1..];
    }
    salida.push_str(resto);
    salida
}

fn limpiar_atributos(etiqueta: &str) -> String {
    let bajo = etiqueta.to_ascii_lowercase();
    if let Some(corte) = bajo.find(" on").filter(|_| bajo.contains('=')) {
        return format!("{}>", &etiqueta[..corte]);
    }
    if bajo.contains("javascript:") {
        let corte = bajo.find(' ').unwrap_or(etiqueta.len() - 1);
        return format!("{}>", &etiqueta[..corte]);
    }
    etiqueta.to_string()
}

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

pub fn sanear_nombre(entrada: &str) -> String {
    let base = entrada.rsplit(['/', '\\']).next().unwrap_or("");
    let limpio: String = base
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || "._- ".contains(*c))
        .collect();
    let limpio = limpio.trim_matches(['.', ' ']);
    if limpio.is_empty() { "archivo".into() } else { limpio.into() }
}
