//! Lectura de una petición HTTP/1.1 desde el socket.
//!
//! Cada rechazo cita el requisito de `spec/conformidad.md` que lo exige. Eso es lo que hace que
//! esta implementación no sea una traducción del código Java: el juez es el contrato.

use std::collections::HashMap;
use std::io::{BufRead, BufReader, Read};
use std::net::TcpStream;

/// Por qué se rechaza una petición. El estado que corresponde a cada motivo lo fija el RFC, no
/// nosotros, así que viaja con el motivo en vez de decidirse en el sitio de la llamada.
#[derive(Debug, PartialEq)]
pub enum Rechazo {
    /// 400 — la petición está mal formada.
    MalFormada(&'static str),
    /// 501 — se entiende la petición pero el método no se implementa.
    NoImplementado,
    /// 505 — la versión no es una que sepamos hablar.
    VersionNoSoportada,
    /// 431 — las cabeceras no caben.
    Demasiado,
}

impl Rechazo {
    pub fn estado(&self) -> u16 {
        match self {
            Rechazo::MalFormada(_) => 400,
            Rechazo::NoImplementado => 501,
            Rechazo::VersionNoSoportada => 505,
            Rechazo::Demasiado => 431,
        }
    }
}

const METODOS: [&str; 9] = [
    "GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS", "PATCH", "TRACE", "CONNECT",
];

const MAX_LINEA: usize = 8 * 1024;
const MAX_CABECERAS: usize = 100;

#[derive(Debug)]
pub struct Peticion {
    pub metodo: String,
    pub destino: String,
    pub version: String,
    pub cabeceras: HashMap<String, String>,
    pub cuerpo: Vec<u8>,
}

impl Peticion {
    pub fn cabecera(&self, nombre: &str) -> Option<&str> {
        self.cabeceras.get(&nombre.to_ascii_lowercase()).map(String::as_str)
    }

    /// El camino sin la cadena de consulta, y con la forma absoluta reducida a su camino.
    pub fn camino(&self) -> &str {
        let sin_consulta = self.destino.split('?').next().unwrap_or("/");
        // HTTP-002: origin-form, absolute-form y asterisk-form son las tres formas válidas.
        if let Some(resto) = sin_consulta.strip_prefix("http://").or_else(|| sin_consulta.strip_prefix("https://")) {
            match resto.find('/') {
                Some(i) => &resto[i..],
                None => "/",
            }
        } else {
            sin_consulta
        }
    }
}

pub fn leer(flujo: &TcpStream) -> Result<Peticion, Rechazo> {
    let mut lector = BufReader::new(flujo);
    let inicial = linea(&mut lector)?;
    let (metodo, destino, version) = partir_linea_inicial(&inicial)?;
    let cabeceras = leer_cabeceras(&mut lector)?;
    comprobar_host(&version, &cabeceras)?;
    let cuerpo = leer_cuerpo(&mut lector, &cabeceras)?;
    Ok(Peticion { metodo, destino, version, cabeceras, cuerpo })
}

fn linea<R: BufRead>(lector: &mut R) -> Result<String, Rechazo> {
    let mut crudo = Vec::new();
    let mut byte = [0u8; 1];
    loop {
        if crudo.len() > MAX_LINEA {
            return Err(Rechazo::Demasiado);
        }
        match lector.read(&mut byte) {
            Ok(0) => return Err(Rechazo::MalFormada("la conexión se cerró a media línea")),
            Ok(_) => {
                if byte[0] == b'\n' {
                    // El RFC 9112 §2.2 pide CRLF; aceptar LF suelto es la tolerancia habitual,
                    // pero un CR suelto dentro de la línea no se tolera: es la vía del
                    // contrabando cuando hay un intermediario que sí lo parte.
                    if crudo.last() == Some(&b'\r') {
                        crudo.pop();
                    }
                    if crudo.contains(&b'\r') {
                        return Err(Rechazo::MalFormada("CR suelto dentro de la línea"));
                    }
                    return String::from_utf8(crudo)
                        .map_err(|_| Rechazo::MalFormada("la línea no es texto válido"));
                }
                crudo.push(byte[0]);
            }
            Err(_) => return Err(Rechazo::MalFormada("no se pudo leer")),
        }
    }
}

fn partir_linea_inicial(linea: &str) -> Result<(String, String, String), Rechazo> {
    let mut partes = linea.split(' ');
    let metodo = partes.next().unwrap_or("");
    let destino = partes.next().unwrap_or("");
    let version = partes.next().unwrap_or("");
    if metodo.is_empty() || destino.is_empty() || version.is_empty() || partes.next().is_some() {
        // HTTP-004: una línea sin versión —o con partes de más— es 400, no 505.
        return Err(Rechazo::MalFormada("la línea inicial no tiene tres partes"));
    }
    // HTTP-007: los métodos son sensibles a mayúsculas. `get` no es `GET`, y responder 501 en vez
    // de tratarlo como GET es lo que impide que un intermediario y nosotros leamos cosas
    // distintas de los mismos octetos.
    if !METODOS.contains(&metodo) {
        return Err(Rechazo::NoImplementado);
    }
    match version {
        "HTTP/1.1" | "HTTP/1.0" => {}
        v if v.starts_with("HTTP/") => return Err(Rechazo::VersionNoSoportada),
        _ => return Err(Rechazo::MalFormada("la versión no tiene la forma HTTP/x.y")),
    }
    Ok((metodo.to_string(), destino.to_string(), version.to_string()))
}

fn leer_cabeceras<R: BufRead>(lector: &mut R) -> Result<HashMap<String, String>, Rechazo> {
    let mut cabeceras: HashMap<String, String> = HashMap::new();
    let mut vistas: Vec<String> = Vec::new();
    loop {
        let l = linea(lector)?;
        if l.is_empty() {
            return Ok(cabeceras);
        }
        if vistas.len() >= MAX_CABECERAS {
            return Err(Rechazo::Demasiado);
        }
        // HTTP-009: una cabecera plegada —que empieza por espacio— se rechaza. El RFC 9112 §5.2
        // la retiró justamente porque los intermediarios la despliegan de formas distintas.
        if l.starts_with(' ') || l.starts_with('\t') {
            return Err(Rechazo::MalFormada("cabecera plegada"));
        }
        let corte = l.find(':').ok_or(Rechazo::MalFormada("cabecera sin dos puntos"))?;
        let nombre = &l[..corte];
        let valor = &l[corte + 1..];
        // HTTP-008: no se admite espacio entre el nombre y los dos puntos.
        if nombre.is_empty() || nombre.ends_with(' ') || nombre.ends_with('\t') {
            return Err(Rechazo::MalFormada("espacio antes de los dos puntos"));
        }
        // HTTP-010: el nombre solo puede llevar caracteres de token.
        if !nombre.bytes().all(es_token) {
            return Err(Rechazo::MalFormada("el nombre lleva caracteres fuera del token"));
        }
        // HTTP-011: un byte nulo en el valor se rechaza.
        if valor.bytes().any(|b| b == 0 || (b < 0x20 && b != b'\t') || b == 0x7f) {
            return Err(Rechazo::MalFormada("byte de control en el valor"));
        }
        let clave = nombre.to_ascii_lowercase();
        let valor = valor.trim_matches(|c| c == ' ' || c == '\t').to_string();
        // HTTP-014 y HTTP-016: Host y Content-Length duplicados se rechazan; otras cabeceras
        // repetibles se unen con coma, como manda el RFC 9110 §5.3.
        if let Some(previo) = cabeceras.get(&clave) {
            if clave == "host" || clave == "content-length" {
                if clave == "host" || previo != &valor {
                    return Err(Rechazo::MalFormada("cabecera única repetida con valor distinto"));
                }
            } else {
                cabeceras.insert(clave.clone(), format!("{previo}, {valor}"));
                continue;
            }
        }
        vistas.push(clave.clone());
        cabeceras.insert(clave, valor);
    }
}

fn es_token(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b"!#$%&'*+-.^_`|~".contains(&b)
}

fn comprobar_host(version: &str, cabeceras: &HashMap<String, String>) -> Result<(), Rechazo> {
    // HTTP-013: HTTP/1.1 exige Host. HTTP/1.0 puede no traerlo.
    if version == "HTTP/1.1" && !cabeceras.contains_key("host") {
        return Err(Rechazo::MalFormada("HTTP/1.1 sin Host"));
    }
    Ok(())
}

fn leer_cuerpo<R: BufRead>(
    lector: &mut R,
    cabeceras: &HashMap<String, String>,
) -> Result<Vec<u8>, Rechazo> {
    if let Some(te) = cabeceras.get("transfer-encoding") {
        // Las dos cabeceras juntas dicen dos longitudes distintas del mismo cuerpo. Si nosotros
        // creemos a una y el intermediario a la otra, leemos dos peticiones distintas de los
        // mismos octetos: es el contrabando de peticiones. El RFC 9112 §6.3 manda rechazar.
        if cabeceras.contains_key("content-length") {
            return Err(Rechazo::MalFormada("content-length y transfer-encoding juntos"));
        }
        // HTTP-019: `chunked` tiene que ser la última codificación. Aceptarlo en otra posición
        // es la puerta del contrabando cuando un intermediario lee otra cosa.
        let ultima = te.rsplit(',').next().unwrap_or("").trim().to_ascii_lowercase();
        if ultima != "chunked" {
            return Err(Rechazo::MalFormada("chunked no es la última codificación"));
        }
        return leer_por_trozos(lector);
    }
    let Some(cl) = cabeceras.get("content-length") else {
        return Ok(Vec::new());
    };
    // HTTP-017 y HTTP-018: negativo o no numérico se rechaza. `parse::<u64>` cubre los dos.
    let largo: u64 = cl.parse().map_err(|_| Rechazo::MalFormada("content-length inválido"))?;
    let mut cuerpo = vec![0u8; largo as usize];
    lector
        .read_exact(&mut cuerpo)
        .map_err(|_| Rechazo::MalFormada("el cuerpo es más corto que content-length"))?;
    Ok(cuerpo)
}

fn leer_por_trozos<R: BufRead>(lector: &mut R) -> Result<Vec<u8>, Rechazo> {
    let mut cuerpo = Vec::new();
    loop {
        let cabecera = linea(lector)?;
        let tamano_txt = cabecera.split(';').next().unwrap_or("").trim();
        // HTTP-020: un tamaño que no es hexadecimal se rechaza.
        let tamano = usize::from_str_radix(tamano_txt, 16)
            .map_err(|_| Rechazo::MalFormada("tamaño de trozo no hexadecimal"))?;
        if tamano == 0 {
            while !linea(lector)?.is_empty() {} // trailers, que se descartan
            return Ok(cuerpo);
        }
        let mut trozo = vec![0u8; tamano];
        lector.read_exact(&mut trozo).map_err(|_| Rechazo::MalFormada("trozo incompleto"))?;
        cuerpo.extend_from_slice(&trozo);
        if !linea(lector)?.is_empty() {
            return Err(Rechazo::MalFormada("falta el CRLF tras el trozo"));
        }
    }
}
