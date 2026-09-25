//! Lo que una acción recibe y lo que devuelve.
//!
//! El contrato no dice cómo se llama nada de esto —`spec/ruteo.md` lo deja explícitamente fuera—,
//! solo qué tiene que estar disponible y qué forma toma la respuesta.

use crate::peticion::Peticion;
use crate::sesion::Sesion;
use std::collections::HashMap;
use std::cell::RefCell;
use std::sync::{Arc, Mutex};

pub struct Respuesta {
    pub estado: u16,
    pub tipo: String,
    pub cuerpo: Vec<u8>,
    pub extra: Vec<(String, String)>,
}

impl Respuesta {
    fn con(estado: u16, tipo: &str, cuerpo: Vec<u8>) -> Respuesta {
        Respuesta { estado, tipo: tipo.into(), cuerpo, extra: Vec::new() }
    }

    /// RUT-019: una cadena se sirve como texto plano.
    pub fn texto(cuerpo: &str) -> Respuesta {
        Respuesta::con(200, "text/plain; charset=utf-8", cuerpo.as_bytes().to_vec())
    }

    /// RUT-019: un objeto se serializa a JSON. Sin serializador genérico —eso pide derivar, y
    /// derivar en Rust pide una caja externa—, así que la acción entrega el JSON ya formado.
    pub fn json(cuerpo: &str) -> Respuesta {
        Respuesta::con(200, "application/json; charset=utf-8", cuerpo.as_bytes().to_vec())
    }

    pub fn html(cuerpo: &str) -> Respuesta {
        Respuesta::con(200, "text/html; charset=utf-8", cuerpo.as_bytes().to_vec())
    }

    /// RUT-020: devolver nada responde 204.
    pub fn nada() -> Respuesta {
        Respuesta::con(204, "text/plain; charset=utf-8", Vec::new())
    }

    /// RUT-021: una redirección es 302 con Location.
    pub fn redirigir(a: &str) -> Respuesta {
        let mut r = Respuesta::con(302, "text/plain; charset=utf-8", Vec::new());
        r.extra.push(("Location".into(), a.into()));
        r
    }

    /// RUT-022: la descarga sanea el nombre antes de ponerlo en la cabecera. No es opcional: por
    /// ahí se intentó colar una cookie.
    pub fn descarga(cuerpo: Vec<u8>, nombre: &str, tipo: &str) -> Respuesta {
        let limpio = crate::seguridad::sanear_nombre(nombre);
        let mut r = Respuesta::con(200, tipo, cuerpo);
        r.extra.push((
            "Content-Disposition".into(),
            format!("attachment; filename=\"{limpio}\""),
        ));
        r
    }

    pub fn estado(estado: u16, cuerpo: &str) -> Respuesta {
        Respuesta::con(estado, "text/plain; charset=utf-8", cuerpo.as_bytes().to_vec())
    }

    pub fn cabecera(mut self, nombre: &str, valor: &str) -> Respuesta {
        self.extra.push((nombre.into(), valor.into()));
        self
    }
}

/// RUT-037: el contexto está disponible para la acción sin declararlo como dependencia.
pub struct Contexto<'p> {
    pub peticion: &'p Peticion,
    pub variables: HashMap<String, String>,
    /// La sesión que llegó con la petición, si llegó alguna.
    sesion: Option<Arc<Mutex<Sesion>>>,
    /// La que abrió la acción durante esta petición. Sin esto, una sesión creada dentro de la
    /// acción no llega al punto que emite la cookie y el cliente nunca la recibe: es la familia
    /// de `SES-010` otra vez, con otra cara.
    abierta: RefCell<Option<Arc<Mutex<Sesion>>>>,
    abrir: Box<dyn Fn() -> Option<Arc<Mutex<Sesion>>> + 'p>,
}

impl<'p> Contexto<'p> {
    pub(crate) fn nuevo(
        peticion: &'p Peticion,
        variables: HashMap<String, String>,
        sesion: Option<Arc<Mutex<Sesion>>>,
        abrir: Box<dyn Fn() -> Option<Arc<Mutex<Sesion>>> + 'p>,
    ) -> Contexto<'p> {
        Contexto { peticion, variables, sesion, abierta: RefCell::new(None), abrir }
    }

    /// RUT-002: la variable de ruta, por su nombre.
    pub fn variable(&self, nombre: &str) -> Option<&str> {
        self.variables.get(nombre).map(String::as_str)
    }

    /// RUT-014 y RUT-015: convertida al tipo que la acción pida. `Err` es 400 y no 500: el
    /// cliente mandó mal la petición.
    pub fn variable_como<T: std::str::FromStr>(&self, nombre: &str) -> Result<T, Respuesta> {
        self.variable(nombre)
            .ok_or_else(|| Respuesta::estado(400, "falta la variable"))?
            .parse()
            .map_err(|_| Respuesta::estado(400, "la variable no tiene el tipo esperado"))
    }

    pub fn consulta(&self, nombre: &str) -> Option<String> {
        let (_, cadena) = self.peticion.destino.split_once('?')?;
        cadena.split('&').find_map(|par| {
            let (k, v) = par.split_once('=')?;
            (k == nombre).then(|| v.replace('+', " "))
        })
    }

    /// RUT-016: un defecto declarado se distingue de «sin defecto». `None` significa ausente y
    /// `Some("")` significa presente y vacío, que no es lo mismo para una búsqueda.
    pub fn consulta_o(&self, nombre: &str, defecto: &str) -> String {
        self.consulta(nombre).unwrap_or_else(|| defecto.to_string())
    }

    pub fn cuerpo_texto(&self) -> String {
        String::from_utf8_lossy(&self.peticion.cuerpo).into_owned()
    }

    /// La sesión de esta petición, si ya existe. No la crea: `SES-001` dice que leer no puede
    /// crear, porque eso convierte a cualquier rastreador en un generador de sesiones huérfanas.
    pub fn sesion(&self) -> Option<&Arc<Mutex<Sesion>>> {
        self.sesion.as_ref()
    }

    /// Abre una sesión. Solo esto la crea, y solo cuando la aplicación lo pide.
    ///
    /// Llamarlo dos veces devuelve la misma: abrir sesión es idempotente dentro de una petición,
    /// porque si no lo fuera la segunda llamada dejaría huérfana a la primera.
    pub fn abrir_sesion(&self) -> Option<Arc<Mutex<Sesion>>> {
        if let Some(ya) = self.abierta.borrow().as_ref() {
            return Some(Arc::clone(ya));
        }
        if let Some(previa) = &self.sesion {
            return Some(Arc::clone(previa));
        }
        let nueva = (self.abrir)()?;
        *self.abierta.borrow_mut() = Some(Arc::clone(&nueva));
        Some(nueva)
    }

    /// La que haya que usar al emitir la cookie: la abierta si la hubo, si no la que llegó.
    pub(crate) fn sesion_final(&self) -> Option<Arc<Mutex<Sesion>>> {
        self.abierta.borrow().clone().or_else(|| self.sesion.clone())
    }
}
