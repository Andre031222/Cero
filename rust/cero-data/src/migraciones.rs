use crate::{Conexion, Fallo, Resultado};

pub fn sentencias(guion: &str) -> Vec<String> {
    let mut salida = Vec::new();
    let mut actual = String::new();
    let mut comilla: Option<char> = None;
    let mut linea_comentada = false;
    let mut anterior = '\0';

    for c in guion.chars() {
        if linea_comentada {
            if c == '\n' {
                linea_comentada = false;
                actual.push(c);
            }
            continue;
        }
        match comilla {
            Some(q) => {
                actual.push(c);
                if c == q && anterior != '\\' {
                    comilla = None;
                }
            }
            None => {
                if c == '-' && anterior == '-' {
                    actual.pop();
                    linea_comentada = true;
                } else if c == '\'' || c == '"' {
                    comilla = Some(c);
                    actual.push(c);
                } else if c == ';' {
                    if !actual.trim().is_empty() {
                        salida.push(actual.trim().to_string());
                    }
                    actual.clear();
                } else {
                    actual.push(c);
                }
            }
        }
        anterior = c;
    }
    if !actual.trim().is_empty() {
        salida.push(actual.trim().to_string());
    }
    salida
}

pub struct Migracion {
    pub version: u32,
    pub nombre: String,
    pub guion: String,
}

pub fn aplicar(c: &mut dyn Conexion, mut pendientes: Vec<Migracion>, ya: &[u32]) -> Resultado<u32> {
    pendientes.sort_by_key(|m| m.version);
    let mut hechas = 0;
    for m in pendientes {
        if ya.contains(&m.version) {
            continue;
        }
        for s in sentencias(&m.guion) {
            c.ejecutar(&s, &[]).map_err(|e| {
                Fallo(format!("migración {} ({}) falló: {e}", m.version, m.nombre))
            })?;
        }
        hechas += 1;
    }
    Ok(hechas)
}
