//! Una implementación en memoria del contrato.
//!
//! No es un motor de verdad ni pretende serlo: existe para que `cero-data` se pueda probar entero
//! sin base de datos, y para que quien vaya a escribir un driver tenga contra qué comparar. Java
//! usa H2 para lo mismo, que es una dependencia de prueba; aquí no hace falta ninguna.

use crate::{Conexion, Fallo, Fila, Fuente, Resultado, Valor};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};

#[derive(Default)]
struct Datos {
    tablas: HashMap<String, Vec<Fila>>,
}

#[derive(Clone, Default)]
pub struct EnMemoria {
    datos: Arc<Mutex<Datos>>,
}

impl EnMemoria {
    pub fn nueva() -> EnMemoria {
        EnMemoria::default()
    }

    pub fn sembrar(&self, tabla: &str, filas: Vec<Fila>) {
        self.datos.lock().expect("envenenado").tablas.insert(tabla.into(), filas);
    }

    pub fn cuantas(&self, tabla: &str) -> usize {
        self.datos.lock().map(|d| d.tablas.get(tabla).map_or(0, Vec::len)).unwrap_or(0)
    }
}

impl Fuente for EnMemoria {
    fn conexion(&self) -> Resultado<Box<dyn Conexion>> {
        Ok(Box::new(ConexionMemoria { datos: Arc::clone(&self.datos), copia: None }))
    }
}

struct ConexionMemoria {
    datos: Arc<Mutex<Datos>>,
    /// La copia que se restaura al deshacer. Que exista es lo que hace que la transacción sea
    /// comprobable sin motor: `transaccion()` se puede verificar de verdad.
    copia: Option<HashMap<String, Vec<Fila>>>,
}

/// Las cuatro órdenes que esta implementación entiende, ya troceadas.
///
/// Deliberadamente limitado: esto no es un motor, y aceptar SQL arbitrario daría la falsa
/// sensación de que lo es. Lo que entiende es exactamente lo que `Repositorio` emite.
enum Orden {
    Select { tabla: String, columna: Option<String> },
    Delete { tabla: String, columna: String },
    Insert { tabla: String, columnas: Vec<String> },
    Update { tabla: String, asigna: Vec<String>, columna: String },
}

/// La palabra que sigue a `aguja`. Las palabras clave se buscan en `bajo` y el nombre se saca del
/// `sql` original: un identificador tiene que volver **como se escribió**, porque es la clave con
/// la que se guardó la tabla. Bajarlo a minúsculas hacía que `AppSessions` se escribiera en
/// `appsessions` y que quien la pidiera por su nombre no encontrara nada.
fn tras(bajo: &str, sql: &str, aguja: &str) -> Option<String> {
    let desde = bajo.find(aguja)? + aguja.len();
    let palabra: String = sql[desde..]
        .trim_start()
        .chars()
        .take_while(|c| c.is_ascii_alphanumeric() || *c == '_')
        .collect();
    (!palabra.is_empty()).then_some(palabra)
}

/// Los nombres de una lista como `(a, b, c)` o `a = ?, b = ?`.
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
        let datos = self.datos.lock().map_err(|_| Fallo("envenenado".into()))?;
        let filas = datos.tablas.get(&tabla).cloned().unwrap_or_default();
        let Some(col) = columna else {
            let tope = match parametros.first() {
                Some(Valor::Entero(n)) => *n as usize,
                _ => filas.len(),
            };
            return Ok(filas.into_iter().take(tope).collect());
        };
        let buscado = parametros.first().ok_or_else(|| Fallo("falta el parámetro".into()))?;
        Ok(filas.into_iter().filter(|f| f.valor(&col) == Some(buscado)).collect())
    }

    fn ejecutar(&mut self, sql: &str, parametros: &[Valor]) -> Resultado<u64> {
        let orden = trocear(sql).ok_or_else(|| Fallo(format!("no sé leer: {sql}")))?;
        let mut datos = self.datos.lock().map_err(|_| Fallo("envenenado".into()))?;
        match orden {
            Orden::Insert { tabla, columnas } => {
                if columnas.len() != parametros.len() {
                    return Err(Fallo("el INSERT no cuadra con sus parámetros".into()));
                }
                let pares = columnas.iter().map(String::as_str).zip(parametros.iter().cloned());
                datos.tablas.entry(tabla).or_default().push(Fila::de(pares.collect()));
                Ok(1)
            }
            Orden::Update { tabla, asigna, columna } => {
                // El valor del `WHERE` es el **último** parámetro: los de antes son el `SET`.
                let (nuevos, clave) = parametros
                    .split_at(parametros.len().checked_sub(1).ok_or_else(|| Fallo("UPDATE sin parámetros".into()))?);
                if asigna.len() != nuevos.len() {
                    return Err(Fallo("el UPDATE no cuadra con sus parámetros".into()));
                }
                let filas = datos.tablas.entry(tabla).or_default();
                let mut tocadas = 0;
                for f in filas.iter_mut().filter(|f| f.valor(&columna) == Some(&clave[0])) {
                    for (c, v) in asigna.iter().zip(nuevos.iter()) {
                        f.poner(c, v.clone());
                    }
                    tocadas += 1;
                }
                Ok(tocadas)
            }
            Orden::Delete { tabla, columna } => {
                // Una migración escribe el valor en el propio SQL, y eso es correcto: la escribe
                // el programador, no el usuario. Sin parámetro, se lee el literal tras el `=`.
                let propio;
                let buscado = match parametros.first() {
                    Some(v) => v,
                    None => {
                        propio = literal(sql).ok_or_else(|| Fallo("falta el parámetro".into()))?;
                        &propio
                    }
                };
                let filas = datos.tablas.entry(tabla).or_default();
                let antes = filas.len();
                filas.retain(|f| f.valor(&columna) != Some(buscado));
                Ok((antes - filas.len()) as u64)
            }
            Orden::Select { .. } => Err(Fallo("ejecutar no es para SELECT".into())),
        }
    }

    fn empezar(&mut self) -> Resultado<()> {
        let datos = self.datos.lock().map_err(|_| Fallo("envenenado".into()))?;
        self.copia = Some(datos.tablas.clone());
        Ok(())
    }

    fn confirmar(&mut self) -> Resultado<()> {
        self.copia = None;
        Ok(())
    }

    fn deshacer(&mut self) -> Resultado<()> {
        if let Some(copia) = self.copia.take() {
            self.datos.lock().map_err(|_| Fallo("envenenado".into()))?.tablas = copia;
        }
        Ok(())
    }
}


/// El valor escrito en el propio SQL, para las migraciones. No se usa nunca con entrada del
/// usuario: esa llega siempre como parámetro, y por eso `consultar` no lo admite.
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
