pub mod memoria;
pub mod migraciones;
pub mod sesiones;

use std::collections::HashMap;
use std::error::Error;
use std::fmt;
use std::iter;

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

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Fila {
    columnas: HashMap<String, Valor>,
}

impl Fila {
    pub fn de(pares: Vec<(&str, Valor)>) -> Fila {
        Fila { columnas: pares.into_iter().map(|(k, v)| (k.into(), v)).collect() }
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

    pub fn poner(&mut self, columna: &str, valor: Valor) {
        self.columnas.insert(columna.into(), valor);
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

impl Error for Fallo {}

pub type Resultado<T> = Result<T, Fallo>;

pub trait Conexion: Send {
    fn consultar(&mut self, sql: &str, parametros: &[Valor]) -> Resultado<Vec<Fila>>;
    fn ejecutar(&mut self, sql: &str, parametros: &[Valor]) -> Resultado<u64>;
    fn empezar(&mut self) -> Resultado<()>;
    fn confirmar(&mut self) -> Resultado<()>;
    fn deshacer(&mut self) -> Resultado<()>;
}

pub trait Fuente: Send + Sync {
    fn conexion(&self) -> Resultado<Box<dyn Conexion>>;
}

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
            let _ = c.deshacer();
            Err(e)
        }
    }
}

pub struct Repositorio<'f> {
    fuente: &'f dyn Fuente,
    tabla: String,
    clave: String,
}

impl<'f> Repositorio<'f> {
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
        self.fuente.conexion()?.consultar(&sql, &[Valor::Entero(i64::from(limite))])
    }

    pub fn guardar(&self, fila: &Fila) -> Resultado<()> {
        let clave = fila
            .valor(&self.clave)
            .ok_or_else(|| Fallo(format!("la fila no trae su clave {}", self.clave)))?;
        let otras: Vec<&String> = fila.columnas().into_iter().filter(|c| **c != self.clave).collect();
        if otras.is_empty() {
            return Err(Fallo("una fila con solo la clave no tiene nada que guardar".into()));
        }
        let valores: Vec<Valor> = otras.iter().filter_map(|c| fila.valor(c).cloned()).collect();

        let asigna: Vec<String> = otras.iter().map(|c| format!("{c} = ?")).collect();
        let sql = format!("UPDATE {} SET {} WHERE {} = ?", self.tabla, asigna.join(", "), self.clave);
        let mut parametros = valores.clone();
        parametros.push(clave.clone());
        let mut c = self.fuente.conexion()?;
        if c.ejecutar(&sql, &parametros)? > 0 {
            return Ok(());
        }

        let columnas: Vec<&str> =
            iter::once(self.clave.as_str()).chain(otras.iter().map(|c| c.as_str())).collect();
        let huecos = vec!["?"; columnas.len()].join(", ");
        let sql = format!("INSERT INTO {} ({}) VALUES ({})", self.tabla, columnas.join(", "), huecos);
        let mut parametros = vec![clave.clone()];
        parametros.extend(valores);
        c.ejecutar(&sql, &parametros)?;
        Ok(())
    }

    pub fn borrar(&self, valor: Valor) -> Resultado<u64> {
        let sql = format!("DELETE FROM {} WHERE {} = ?", self.tabla, self.clave);
        self.fuente.conexion()?.ejecutar(&sql, &[valor])
    }
}
