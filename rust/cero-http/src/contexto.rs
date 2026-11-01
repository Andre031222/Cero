use crate::fallo::Fallo;
use crate::json::{self, Json};
use crate::peticion::Peticion;
use crate::registro::Registro;
use crate::seguridad::CLAVE_CSRF;
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

    pub fn texto(cuerpo: &str) -> Respuesta {
        Respuesta::con(200, "text/plain; charset=utf-8", cuerpo.as_bytes().to_vec())
    }

    pub fn json(v: Json) -> Respuesta {
        Respuesta::con(200, "application/json; charset=utf-8", v.escribir().into_bytes())
    }

    pub fn json_crudo(cuerpo: &str) -> Respuesta {
        Respuesta::con(200, "application/json; charset=utf-8", cuerpo.as_bytes().to_vec())
    }

    pub fn html(cuerpo: &str) -> Respuesta {
        Respuesta::con(200, "text/html; charset=utf-8", cuerpo.as_bytes().to_vec())
    }

    pub fn nada() -> Respuesta {
        Respuesta::con(204, "text/plain; charset=utf-8", Vec::new())
    }

    pub fn redirigir(a: &str) -> Respuesta {
        let mut r = Respuesta::con(302, "text/plain; charset=utf-8", Vec::new());
        r.extra.push(("Location".into(), a.into()));
        r
    }

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

pub struct Contexto<'p> {
    pub peticion: &'p Peticion,
    pub variables: HashMap<String, String>,
    sesion: Option<Arc<Mutex<Sesion>>>,
    abierta: RefCell<Option<Arc<Mutex<Sesion>>>>,
    abrir: Box<dyn Fn() -> Option<Arc<Mutex<Sesion>>> + 'p>,
    registro: &'p Registro,
}

impl<'p> Contexto<'p> {
    pub(crate) fn nuevo(
        peticion: &'p Peticion,
        variables: HashMap<String, String>,
        sesion: Option<Arc<Mutex<Sesion>>>,
        abrir: Box<dyn Fn() -> Option<Arc<Mutex<Sesion>>> + 'p>,
        registro: &'p Registro,
    ) -> Contexto<'p> {
        Contexto { peticion, variables, sesion, abierta: RefCell::new(None), abrir, registro }
    }

    pub fn registro(&self) -> &Registro {
        self.registro
    }

    pub fn cabecera(&self, nombre: &str) -> Option<&str> {
        self.peticion.cabecera(nombre)
    }

    pub fn variable(&self, nombre: &str) -> Option<&str> {
        self.variables.get(nombre).map(String::as_str)
    }

    pub fn variable_como<T: std::str::FromStr>(&self, nombre: &str) -> Result<T, Fallo> {
        self.variable(nombre)
            .ok_or_else(|| Fallo::nuevo(400, "falta la variable"))?
            .parse()
            .map_err(|_| Fallo::nuevo(400, "la variable no tiene el tipo esperado"))
    }

    pub fn consulta(&self, nombre: &str) -> Option<String> {
        let (_, cadena) = self.peticion.destino.split_once('?')?;
        cadena.split('&').find_map(|par| {
            let (k, v) = par.split_once('=')?;
            (k == nombre).then(|| v.replace('+', " "))
        })
    }

    pub fn consulta_o(&self, nombre: &str, defecto: &str) -> String {
        self.consulta(nombre).unwrap_or_else(|| defecto.to_string())
    }

    pub fn cuerpo_texto(&self) -> String {
        String::from_utf8_lossy(&self.peticion.cuerpo).into_owned()
    }

    pub fn cuerpo_json(&self) -> Result<Json, Fallo> {
        json::leer(&self.cuerpo_texto())
            .map_err(|e| Fallo::nuevo(400, &format!("el cuerpo no es JSON válido: {}", e.0)))
    }

    pub fn campo(&self, nombre: &str) -> Option<String> {
        pares(&self.cuerpo_texto()).into_iter().find_map(|(k, v)| (k == nombre).then_some(v))
    }

    pub fn campos(&self) -> Vec<(String, String)> {
        pares(&self.cuerpo_texto())
    }

    pub fn sesion(&self) -> Option<&Arc<Mutex<Sesion>>> {
        self.sesion.as_ref()
    }

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

    pub fn token_csrf(&self) -> Option<String> {
        let sesion = self.abrir_sesion()?;
        let mut g = sesion.lock().ok()?;
        if let Ok(Some(ya)) = g.leer(CLAVE_CSRF) {
            return Some(ya.clone());
        }
        let token = crate::sesion::identificador().ok()?;
        g.poner(CLAVE_CSRF, &token).ok()?;
        Some(token)
    }

    pub(crate) fn sesion_final(&self) -> Option<Arc<Mutex<Sesion>>> {
        self.abierta.borrow().clone().or_else(|| self.sesion.clone())
    }
}


fn pares(cadena: &str) -> Vec<(String, String)> {
    cadena
        .split('&')
        .filter(|p| !p.is_empty())
        .map(|par| {
            let (k, v) = par.split_once('=').unwrap_or((par, ""));
            (desescapar(k), desescapar(v))
        })
        .collect()
}

fn desescapar(s: &str) -> String {
    let bytes = s.replace('+', " ").into_bytes();
    let mut salida = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            let hex = std::str::from_utf8(&bytes[i + 1..i + 3]).unwrap_or("");
            if let Ok(b) = u8::from_str_radix(hex, 16) {
                salida.push(b);
                i += 3;
                continue;
            }
        }
        salida.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&salida).into_owned()
}
