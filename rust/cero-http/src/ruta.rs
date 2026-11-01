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

pub type Captura = HashMap<String, String>;

impl Patron {
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

    pub fn casa(&self, camino: &str) -> Option<Captura> {
        let patron = self.segmentos();
        let partes = trocear(camino);
        let mut captura = Captura::new();
        for (i, seg) in patron.iter().enumerate() {
            match seg {
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
        if partes.len() == patron.len() { Some(captura) } else { None }
    }

    fn literales(&self) -> usize {
        self.segmentos().iter().filter(|s| matches!(s, Segmento::Literal(_))).count()
    }
}

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
    Encontrada(String, Captura),
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
        let buscado = if metodo == "HEAD" { "GET" } else { metodo };

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
