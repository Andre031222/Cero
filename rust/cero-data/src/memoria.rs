use crate::{Conexion, Fallo, Fila, Fuente, Resultado, Valor};
use std::collections::HashMap;
use std::sync::{Arc, Mutex, MutexGuard};

type Tablas = HashMap<String, Vec<Fila>>;

#[derive(Clone, Default)]
pub struct EnMemoria {
    datos: Arc<Mutex<Tablas>>,
}

impl EnMemoria {
    pub fn nueva() -> EnMemoria {
        EnMemoria::default()
    }

    pub fn sembrar(&self, tabla: &str, filas: Vec<Fila>) {
        self.datos.lock().expect("envenenado").insert(tabla.into(), filas);
    }

    pub fn cuantas(&self, tabla: &str) -> usize {
        self.datos.lock().map_or(0, |d| d.get(tabla).map_or(0, Vec::len))
    }
}

impl Fuente for EnMemoria {
    fn conexion(&self) -> Resultado<Box<dyn Conexion>> {
        Ok(Box::new(ConexionMemoria { datos: Arc::clone(&self.datos), copia: None }))
    }
}

struct ConexionMemoria {
    datos: Arc<Mutex<Tablas>>,
    copia: Option<Tablas>,
}

impl ConexionMemoria {
    fn tablas(&self) -> Resultado<MutexGuard<'_, Tablas>> {
        self.datos.lock().map_err(|_| Fallo("envenenado".into()))
    }
}

enum Orden {
    Select { tabla: String, columna: Option<String> },
    Delete { tabla: String, columna: String },
    Insert { tabla: String, columnas: Vec<String> },
    Update { tabla: String, asigna: Vec<String>, columna: String },
}

fn tras(bajo: &str, sql: &str, aguja: &str) -> Option<String> {
    let desde = bajo.find(aguja)? + aguja.len();
    let palabra: String = sql[desde..]
        .trim_start()
        .chars()
        .take_while(|c| c.is_ascii_alphanumeric() || *c == '_')
        .collect();
    (!palabra.is_empty()).then_some(palabra)
}

fn nombres(trozo: &str) -> Vec<String> {
    trozo
        .split(',')
        .filter_map(|p| {
            let limpio: String = p
                .trim()
                .trim_start_matches('(')
                .chars()
                .take_while(|c| c.is_ascii_alphanumeric() || *c == '_')
                .collect();
            (!limpio.is_empty()).then_some(limpio)
        })
        .collect()
}

fn trocear(sql: &str) -> Option<Orden> {
    let bajo = sql.to_ascii_lowercase();
    let columna = || tras(&bajo, sql, " where ");
    match bajo.split_whitespace().next()? {
        "select" => Some(Orden::Select { tabla: tras(&bajo, sql, " from ")?, columna: columna() }),
        "delete" => Some(Orden::Delete { tabla: tras(&bajo, sql, " from ")?, columna: columna()? }),
        "insert" => {
            let abre = bajo.find('(')?;
            let cierra = bajo.find(')')?;
            Some(Orden::Insert {
                tabla: tras(&bajo, sql, "insert into ")?,
                columnas: nombres(&sql[abre + 1..cierra]),
            })
        }
        "update" => {
            let set = bajo.find(" set ")? + 5;
            let hasta = bajo.find(" where ")?;
            Some(Orden::Update {
                tabla: tras(&bajo, sql, "update ")?,
                asigna: nombres(&sql[set..hasta]),
                columna: columna()?,
            })
        }
        _ => None,
    }
}

impl Conexion for ConexionMemoria {
    fn consultar(&mut self, sql: &str, parametros: &[Valor]) -> Resultado<Vec<Fila>> {
        let Some(Orden::Select { tabla, columna }) = trocear(sql) else {
            return Err(Fallo(format!("consultar espera SELECT: {sql}")));
        };
        let datos = self.tablas()?;
        let filas = datos.get(&tabla).map(Vec::as_slice).unwrap_or_default();
        let Some(col) = columna else {
            let tope = match parametros.first() {
                Some(Valor::Entero(n)) => *n as usize,
                _ => filas.len(),
            };
            return Ok(filas.iter().take(tope).cloned().collect());
        };
        let buscado = parametros.first().ok_or_else(|| Fallo("falta el parámetro".into()))?;
        Ok(filas.iter().filter(|f| f.valor(&col) == Some(buscado)).cloned().collect())
    }

    fn ejecutar(&mut self, sql: &str, parametros: &[Valor]) -> Resultado<u64> {
        let orden = trocear(sql).ok_or_else(|| Fallo(format!("no sé leer: {sql}")))?;
        let mut datos = self.tablas()?;
        match orden {
            Orden::Insert { tabla, columnas } => {
                if columnas.len() != parametros.len() {
                    return Err(Fallo("el INSERT no cuadra con sus parámetros".into()));
                }
                let pares = columnas.iter().map(String::as_str).zip(parametros.iter().cloned());
                datos.entry(tabla).or_default().push(Fila::de(pares.collect()));
                Ok(1)
            }
            Orden::Update { tabla, asigna, columna } => {
                let (clave, nuevos) =
                    parametros.split_last().ok_or_else(|| Fallo("UPDATE sin parámetros".into()))?;
                if asigna.len() != nuevos.len() {
                    return Err(Fallo("el UPDATE no cuadra con sus parámetros".into()));
                }
                let filas = datos.entry(tabla).or_default();
                let mut tocadas = 0;
                for f in filas.iter_mut().filter(|f| f.valor(&columna) == Some(clave)) {
                    for (c, v) in asigna.iter().zip(nuevos) {
                        f.poner(c, v.clone());
                    }
                    tocadas += 1;
                }
                Ok(tocadas)
            }
            Orden::Delete { tabla, columna } => {
                let buscado = match parametros.first() {
                    Some(v) => v.clone(),
                    None => literal(sql).ok_or_else(|| Fallo("falta el parámetro".into()))?,
                };
                let filas = datos.entry(tabla).or_default();
                let antes = filas.len();
                filas.retain(|f| f.valor(&columna) != Some(&buscado));
                Ok((antes - filas.len()) as u64)
            }
            Orden::Select { .. } => Err(Fallo("ejecutar no es para SELECT".into())),
        }
    }

    fn empezar(&mut self) -> Resultado<()> {
        let copia = self.tablas()?.clone();
        self.copia = Some(copia);
        Ok(())
    }

    fn confirmar(&mut self) -> Resultado<()> {
        self.copia = None;
        Ok(())
    }

    fn deshacer(&mut self) -> Resultado<()> {
        if let Some(copia) = self.copia.take() {
            *self.tablas()? = copia;
        }
        Ok(())
    }
}

fn literal(sql: &str) -> Option<Valor> {
    let tras_igual = sql.rsplit('=').next()?.trim().trim_end_matches(';').trim();
    if tras_igual == "?" || tras_igual.is_empty() {
        return None;
    }
    if let Some(t) = tras_igual.strip_prefix('\'').and_then(|t| t.strip_suffix('\'')) {
        return Some(Valor::Texto(t.into()));
    }
    tras_igual.parse::<i64>().ok().map(Valor::Entero)
}
