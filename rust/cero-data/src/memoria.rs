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

/// Parte `SELECT * FROM t WHERE c = ?` en sus piezas. Es deliberadamente limitado: esto no es un
/// motor, y aceptar SQL arbitrario aquí daría una falsa sensación de que lo es.
fn trocear(sql: &str) -> Option<(String, String, Option<String>, Option<u32>)> {
    let bajo = sql.to_ascii_lowercase();
    let verbo = bajo.split_whitespace().next()?.to_string();
    let i = bajo.find(" from ").or_else(|| bajo.find("delete from "))?;
    let resto = &sql[i + if bajo[i..].starts_with(" from ") { 6 } else { 12 }..];
    let tabla = resto.split_whitespace().next()?.to_string();
    let columna = bajo
        .find(" where ")
        .and_then(|w| sql[w + 7..].split_whitespace().next().map(str::to_string));
    let limite = bajo
        .find(" limit ")
        .map(|_| u32::MAX); // el valor real llega como parámetro
    Some((verbo, tabla, columna, limite))
}

impl Conexion for ConexionMemoria {
    fn consultar(&mut self, sql: &str, parametros: &[Valor]) -> Resultado<Vec<Fila>> {
        let (verbo, tabla, columna, _) =
            trocear(sql).ok_or_else(|| Fallo(format!("no sé leer: {sql}")))?;
        if verbo != "select" {
            return Err(Fallo(format!("consultar espera SELECT, no {verbo}")));
        }
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
        let (verbo, tabla, columna, _) =
            trocear(sql).ok_or_else(|| Fallo(format!("no sé leer: {sql}")))?;
        if verbo != "delete" {
            return Err(Fallo(format!("esta implementación solo borra, no {verbo}")));
        }
        let col = columna.ok_or_else(|| Fallo("DELETE sin WHERE no se admite".into()))?;
        // Una migración escribe el valor en el propio SQL, y eso es correcto: la escribe el
        // programador, no el usuario. Sin parámetro, se lee el literal que sigue al `=`.
        let propio;
        let buscado = match parametros.first() {
            Some(v) => v,
            None => {
                propio = literal(sql).ok_or_else(|| Fallo("falta el parámetro".into()))?;
                &propio
            }
        };
        let mut datos = self.datos.lock().map_err(|_| Fallo("envenenado".into()))?;
        let filas = datos.tablas.entry(tabla).or_default();
        let antes = filas.len();
        filas.retain(|f| f.valor(&col) != Some(buscado));
        Ok((antes - filas.len()) as u64)
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
