use std::collections::HashMap;
use std::io::BufRead;

#[derive(Debug, PartialEq)]
pub enum Rechazo {
    MalFormada(&'static str),
    NoImplementado,
    VersionNoSoportada,
    Demasiado,
    CuerpoGrande,
}

impl Rechazo {
    pub fn estado(&self) -> u16 {
        match self {
            Rechazo::MalFormada(_) => 400,
            Rechazo::NoImplementado => 501,
            Rechazo::VersionNoSoportada => 505,
            Rechazo::Demasiado => 431,
            Rechazo::CuerpoGrande => 413,
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

    pub fn camino(&self) -> &str {
        let sin_consulta = self.destino.split('?').next().unwrap_or("/");
        let absoluto = sin_consulta
            .strip_prefix("http://")
            .or_else(|| sin_consulta.strip_prefix("https://"));
        match absoluto {
            Some(resto) => resto.find('/').map_or("/", |i| &resto[i..]),
            None => sin_consulta,
        }
    }
}

pub fn leer<R: BufRead>(lector: &mut R, max_cuerpo: usize) -> Result<Peticion, Rechazo> {
    let inicial = linea(lector)?;
    let (metodo, destino, version) = partir_linea_inicial(&inicial)?;
    let cabeceras = leer_cabeceras(lector)?;
    comprobar_host(&version, &cabeceras)?;
    let cuerpo = leer_cuerpo(lector, &cabeceras, max_cuerpo)?;
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
        return Err(Rechazo::MalFormada("la línea inicial no tiene tres partes"));
    }
    if !METODOS.contains(&metodo) {
        return Err(Rechazo::NoImplementado);
    }
    match version {
        "HTTP/1.1" | "HTTP/1.0" => {}
        v if v.starts_with("HTTP/") => return Err(Rechazo::VersionNoSoportada),
        _ => return Err(Rechazo::MalFormada("la versión no tiene la forma HTTP/x.y")),
    }
    Ok((metodo.into(), destino.into(), version.into()))
}

fn leer_cabeceras<R: BufRead>(lector: &mut R) -> Result<HashMap<String, String>, Rechazo> {
    let mut cabeceras: HashMap<String, String> = HashMap::new();
    let mut vistas = 0;
    loop {
        let l = linea(lector)?;
        if l.is_empty() {
            return Ok(cabeceras);
        }
        if vistas >= MAX_CABECERAS {
            return Err(Rechazo::Demasiado);
        }
        if l.starts_with(' ') || l.starts_with('\t') {
            return Err(Rechazo::MalFormada("cabecera plegada"));
        }
        let corte = l.find(':').ok_or(Rechazo::MalFormada("cabecera sin dos puntos"))?;
        let nombre = &l[..corte];
        let valor = &l[corte + 1..];
        if nombre.is_empty() || nombre.ends_with(' ') || nombre.ends_with('\t') {
            return Err(Rechazo::MalFormada("espacio antes de los dos puntos"));
        }
        if !nombre.bytes().all(es_token) {
            return Err(Rechazo::MalFormada("el nombre lleva caracteres fuera del token"));
        }
        if valor.bytes().any(|b| b == 0 || (b < 0x20 && b != b'\t') || b == 0x7f) {
            return Err(Rechazo::MalFormada("byte de control en el valor"));
        }
        let clave = nombre.to_ascii_lowercase();
        let valor = valor.trim_matches(|c| c == ' ' || c == '\t').to_string();
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
        vistas += 1;
        cabeceras.insert(clave, valor);
    }
}

fn es_token(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b"!#$%&'*+-.^_`|~".contains(&b)
}

fn comprobar_host(version: &str, cabeceras: &HashMap<String, String>) -> Result<(), Rechazo> {
    if version == "HTTP/1.1" && !cabeceras.contains_key("host") {
        return Err(Rechazo::MalFormada("HTTP/1.1 sin Host"));
    }
    Ok(())
}

fn leer_cuerpo<R: BufRead>(
    lector: &mut R,
    cabeceras: &HashMap<String, String>,
    max_cuerpo: usize,
) -> Result<Vec<u8>, Rechazo> {
    if let Some(te) = cabeceras.get("transfer-encoding") {
        if cabeceras.contains_key("content-length") {
            return Err(Rechazo::MalFormada("content-length y transfer-encoding juntos"));
        }
        let ultima = te.rsplit(',').next().unwrap_or("").trim().to_ascii_lowercase();
        if ultima != "chunked" {
            return Err(Rechazo::MalFormada("chunked no es la última codificación"));
        }
        return leer_por_trozos(lector, max_cuerpo);
    }
    let Some(cl) = cabeceras.get("content-length") else {
        return Ok(Vec::new());
    };
    let largo: u64 = cl.parse().map_err(|_| Rechazo::MalFormada("content-length inválido"))?;
    if largo > max_cuerpo as u64 {
        return Err(Rechazo::CuerpoGrande);
    }
    let mut cuerpo = vec![0u8; largo as usize];
    lector
        .read_exact(&mut cuerpo)
        .map_err(|_| Rechazo::MalFormada("el cuerpo es más corto que content-length"))?;
    Ok(cuerpo)
}

fn leer_por_trozos<R: BufRead>(lector: &mut R, max_cuerpo: usize) -> Result<Vec<u8>, Rechazo> {
    let mut cuerpo = Vec::new();
    loop {
        let cabecera = linea(lector)?;
        let tamano_txt = cabecera.split(';').next().unwrap_or("").trim();
        let tamano = usize::from_str_radix(tamano_txt, 16)
            .map_err(|_| Rechazo::MalFormada("tamaño de trozo no hexadecimal"))?;
        if tamano > max_cuerpo - cuerpo.len() {
            return Err(Rechazo::CuerpoGrande);
        }
        if tamano == 0 {
            while !linea(lector)?.is_empty() {}
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
