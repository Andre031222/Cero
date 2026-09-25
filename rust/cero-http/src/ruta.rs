//! Patrones de ruta y resolución, según `spec/ruteo.md` requisitos `RUT-001`–`RUT-011`.
//!
//! El contrato dice qué resuelve el router, no cómo se le dice qué rutas tiene. En Java eso se
//! hace con anotaciones leídas por reflexión; aquí se registran explícitamente, y el contrato se
//! cumple igual. Esa es la primera comprobación de que `spec/` no exportaba una decisión de Java.

use std::collections::HashMap;

#[derive(Debug, PartialEq, Clone, Copy)]
enum Segmento<'p> {
    Literal(&'p str),
    Variable(&'p str),
    Resto,
}

#[derive(Debug)]
pub struct Patron {
    crudo: String,
}

/// Lo que el patrón capturó: variables por nombre, y `*` para el comodín.
pub type Captura = HashMap<String, String>;

impl Patron {
    /// RUT-006 y RUT-007: un patrón inválido se rechaza **al construirlo**, no al resolver. Un
    /// error del programador detectado al arrancar es un error; detectado al resolver es un 500
    /// en producción.
    pub fn nuevo(crudo: &str) -> Result<Patron, String> {
        let partes: Vec<&str> = trocear(crudo);
        for (i, p) in partes.iter().enumerate() {
            if *p == "*" && i != partes.len() - 1 {
                return Err(format!("el comodín solo puede ir al final: {crudo}"));
            }
            if p.starts_with('{') && !p.ends_with('}') {
                return Err(format!("variable sin cerrar: {crudo}"));
            }
            if p.ends_with('}') && !p.starts_with('{') {
                return Err(format!("variable sin abrir: {crudo}"));
            }
            if *p == "{}" {
                return Err(format!("variable sin nombre: {crudo}"));
            }
        }
        Ok(Patron { crudo: crudo.to_string() })
    }

    fn segmentos(&self) -> Vec<Segmento<'_>> {
        trocear(&self.crudo)
            .into_iter()
            .map(|p| {
                if p == "*" {
                    Segmento::Resto
                } else if p.starts_with('{') && p.ends_with('}') {
                    Segmento::Variable(&p[1..p.len() - 1])
                } else {
                    Segmento::Literal(p)
                }
            })
            .collect()
    }

    /// RUT-001 a RUT-005. Devuelve `None` si no casa.
    pub fn casa(&self, camino: &str) -> Option<Captura> {
        let patron = self.segmentos();
        let partes = trocear(camino);
        let mut captura = Captura::new();
        for (i, seg) in patron.iter().enumerate() {
            match seg {
                // RUT-005: el comodín captura el resto, sea uno o varios segmentos.
                Segmento::Resto => {
                    captura.insert("*".into(), partes[i..].join("/"));
                    return Some(captura);
                }
                Segmento::Variable(nombre) => {
                    let valor = partes.get(i)?;
                    captura.insert((*nombre).into(), (*valor).into());
                }
                Segmento::Literal(lit) => {
                    if partes.get(i)? != lit {
                        return None;
                    }
                }
            }
        }
        // RUT-001: ni más segmentos ni menos.
        if partes.len() == patron.len() { Some(captura) } else { None }
    }

    /// Cuántos segmentos literales tiene. RUT-008 lo usa para que el literal gane a la variable.
    fn literales(&self) -> usize {
        self.segmentos().iter().filter(|s| matches!(s, Segmento::Literal(_))).count()
    }
}

/// RUT-004: la barra final se normaliza, así que `/a/7` y `/a/7/` trocean igual.
fn trocear(camino: &str) -> Vec<&str> {
    camino.split('/').filter(|p| !p.is_empty()).collect()
}

pub struct Ruta {
    pub metodo: String,
    pub patron: Patron,
    pub nombre: String,
}

#[derive(Debug, PartialEq)]
pub enum Resolucion {
    /// Casó: el nombre de la acción y lo que capturó el patrón.
    Encontrada(String, Captura),
    /// El camino existe pero no con ese verbo. RUT-009: esto **no** es un 404, y RUT-010 pide
    /// poder enumerar los verbos que sí valen.
    VerboNoPermitido(Vec<String>),
    NoHay,
}

#[derive(Default)]
pub struct Router {
    rutas: Vec<Ruta>,
}

impl Router {
    pub fn nuevo() -> Router {
        Router::default()
    }

    pub fn ruta(mut self, metodo: &str, patron: &str, nombre: &str) -> Result<Router, String> {
        self.rutas.push(Ruta {
            metodo: metodo.to_ascii_uppercase(),
            patron: Patron::nuevo(patron)?,
            nombre: nombre.to_string(),
        });
        Ok(self)
    }

    /// El patrón que atendió el camino, para que las métricas agrupen por él y no por la URL
    /// (OBS-014). Sin esto, `/usuarios/{id}` genera una serie por identificador.
    pub fn patron_de(&self, metodo: &str, camino: &str) -> Option<String> {
        let buscado = if metodo == "HEAD" { "GET" } else { metodo };
        let mut c: Vec<&Ruta> =
            self.rutas.iter().filter(|r| r.patron.casa(camino).is_some()).collect();
        c.sort_by_key(|r| std::cmp::Reverse(r.patron.literales()));
        c.iter()
            .find(|r| r.metodo == buscado)
            .or_else(|| c.first())
            .map(|r| r.patron.crudo.clone())
    }

    pub fn resolver(&self, metodo: &str, camino: &str) -> Resolucion {
        // RUT-011: HEAD se resuelve contra la ruta GET del mismo camino.
        let buscado = if metodo == "HEAD" { "GET" } else { metodo };

        // RUT-008: entre dos patrones que casan gana el que tiene más literales.
        let mut candidatas: Vec<&Ruta> =
            self.rutas.iter().filter(|r| r.patron.casa(camino).is_some()).collect();
        candidatas.sort_by_key(|r| std::cmp::Reverse(r.patron.literales()));

        if let Some(r) = candidatas.iter().find(|r| r.metodo == buscado) {
            return Resolucion::Encontrada(r.nombre.clone(), r.patron.casa(camino).unwrap());
        }
        if candidatas.is_empty() {
            return Resolucion::NoHay;
        }
        let mut verbos: Vec<String> = candidatas.iter().map(|r| r.metodo.clone()).collect();
        if verbos.iter().any(|v| v == "GET") && !verbos.iter().any(|v| v == "HEAD") {
            verbos.push("HEAD".into());
        }
        verbos.sort();
        verbos.dedup();
        Resolucion::VerboNoPermitido(verbos)
    }
}
