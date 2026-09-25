//! Acceso a datos: el contrato, no el driver.
//!
//! En Java este módulo se apoya en `java.sql`, que viene en la plataforma, y la aplicación aporta
//! el driver. La biblioteca estándar de Rust no trae ninguna interfaz de base de datos, así que
//! aquí se define — y el reparto queda idéntico: **el framework pone el contrato y la aplicación
//! pone el driver**, que es la única excepción a «cero dependencias» que el proyecto ya declaraba.
//!
//! La consecuencia práctica es que `cero-data` no depende de ningún motor y se puede probar
//! entero sin base de datos, con la implementación en memoria de [`memoria`].

pub mod memoria;
pub mod migraciones;

use std::collections::HashMap;
use std::fmt;

/// Un valor tal como viaja entre la aplicación y el motor.
///
/// No se admite «cualquier cosa»: un valor que el framework no sepa nombrar es un valor que el
/// driver tendrá que adivinar, y adivinar es como se escapan las inyecciones.
#[derive(Debug, Clone, PartialEq)]
pub enum Valor {
    Nulo,
    Entero(i64),
    Real(f64),
    Texto(String),
    Booleano(bool),
    Binario(Vec<u8>),
}

impl fmt::Display for Valor {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Valor::Nulo => write!(f, ""),
            Valor::Entero(v) => write!(f, "{v}"),
            Valor::Real(v) => write!(f, "{v}"),
            Valor::Texto(v) => write!(f, "{v}"),
            Valor::Booleano(v) => write!(f, "{v}"),
            Valor::Binario(v) => write!(f, "<{} octetos>", v.len()),
        }
    }
}

impl From<i64> for Valor { fn from(v: i64) -> Valor { Valor::Entero(v) } }
impl From<&str> for Valor { fn from(v: &str) -> Valor { Valor::Texto(v.into()) } }
impl From<String> for Valor { fn from(v: String) -> Valor { Valor::Texto(v) } }
impl From<bool> for Valor { fn from(v: bool) -> Valor { Valor::Booleano(v) } }
impl From<f64> for Valor { fn from(v: f64) -> Valor { Valor::Real(v) } }

/// Una fila, accesible por nombre de columna.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Fila {
    columnas: HashMap<String, Valor>,
}

impl Fila {
    pub fn de(pares: Vec<(&str, Valor)>) -> Fila {
        Fila { columnas: pares.into_iter().map(|(k, v)| (k.to_string(), v)).collect() }
    }

    pub fn valor(&self, columna: &str) -> Option<&Valor> {
        self.columnas.get(columna)
    }

    pub fn texto(&self, columna: &str) -> Option<String> {
        match self.valor(columna)? {
            Valor::Nulo => None,
            otro => Some(otro.to_string()),
        }
    }

    pub fn entero(&self, columna: &str) -> Option<i64> {
        match self.valor(columna)? {
            Valor::Entero(v) => Some(*v),
            Valor::Texto(t) => t.parse().ok(),
            _ => None,
        }
    }

    pub fn columnas(&self) -> Vec<&String> {
        let mut v: Vec<&String> = self.columnas.keys().collect();
        v.sort();
        v
    }
}

#[derive(Debug)]
pub struct Fallo(pub String);

impl fmt::Display for Fallo {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}

impl std::error::Error for Fallo {}

pub type Resultado<T> = Result<T, Fallo>;

/// Lo que la aplicación implementa para su motor.
///
/// Las consultas llevan **siempre** parámetros separados. No hay ningún método que acepte SQL ya
/// interpolado: en Java eso es una convención que hay que respetar, aquí no existe la forma de
/// saltársela sin escribirla a mano.
pub trait Conexion: Send {
    fn consultar(&mut self, sql: &str, parametros: &[Valor]) -> Resultado<Vec<Fila>>;
    fn ejecutar(&mut self, sql: &str, parametros: &[Valor]) -> Resultado<u64>;
    fn empezar(&mut self) -> Resultado<()>;
    fn confirmar(&mut self) -> Resultado<()>;
    fn deshacer(&mut self) -> Resultado<()>;
}

/// De dónde salen las conexiones. Un pool, un fichero, lo que la aplicación decida.
pub trait Fuente: Send + Sync {
    fn conexion(&self) -> Resultado<Box<dyn Conexion>>;
}

/// Una transacción que **no puede quedarse a medias por olvido**.
///
/// Si el cuerpo devuelve error o lanza, se deshace. En Java esto es un `try`/`catch` que hay que
/// escribir bien cada vez; aquí la única forma de tener una transacción es pasar por aquí.
pub fn transaccion<T>(
    fuente: &dyn Fuente,
    cuerpo: impl FnOnce(&mut dyn Conexion) -> Resultado<T>,
) -> Resultado<T> {
    let mut c = fuente.conexion()?;
    c.empezar()?;
    match cuerpo(c.as_mut()) {
        Ok(v) => {
            c.confirmar()?;
            Ok(v)
        }
        Err(e) => {
            // Si deshacer también falla, manda el fallo original: es el que explica qué pasó.
            let _ = c.deshacer();
            Err(e)
        }
    }
}

/// Acceso por clave primaria y listado, que es lo que casi toda aplicación necesita.
pub struct Repositorio<'f> {
    fuente: &'f dyn Fuente,
    tabla: String,
    clave: String,
}

impl<'f> Repositorio<'f> {
    /// El nombre de tabla se valida al construir: un identificador no puede ir parametrizado en
    /// SQL, así que si viene de fuera y no se comprueba, es inyección.
    pub fn nuevo(fuente: &'f dyn Fuente, tabla: &str, clave: &str) -> Resultado<Repositorio<'f>> {
        for nombre in [tabla, clave] {
            if nombre.is_empty()
                || !nombre.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
            {
                return Err(Fallo(format!("identificador no válido: {nombre}")));
            }
        }
        Ok(Repositorio { fuente, tabla: tabla.into(), clave: clave.into() })
    }

    pub fn por_clave(&self, valor: Valor) -> Resultado<Option<Fila>> {
        let sql = format!("SELECT * FROM {} WHERE {} = ?", self.tabla, self.clave);
        Ok(self.fuente.conexion()?.consultar(&sql, &[valor])?.into_iter().next())
    }

    pub fn todos(&self, limite: u32) -> Resultado<Vec<Fila>> {
        let sql = format!("SELECT * FROM {} LIMIT ?", self.tabla);
        self.fuente.conexion()?.consultar(&sql, &[Valor::Entero(limite as i64)])
    }

    pub fn borrar(&self, valor: Valor) -> Resultado<u64> {
        let sql = format!("DELETE FROM {} WHERE {} = ?", self.tabla, self.clave);
        self.fuente.conexion()?.ejecutar(&sql, &[valor])
    }
}
