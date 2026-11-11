use std::collections::HashMap;
use std::fs::File;
use std::io::{self, Read};
use std::mem;
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, SystemTime};

pub(crate) fn identificador() -> io::Result<String> {
    let mut crudo = [0u8; 32];
    File::open("/dev/urandom")?.read_exact(&mut crudo)?;
    Ok(crudo.iter().map(|b| format!("{b:02x}")).collect())
}

#[derive(Debug)]
pub struct Sesion {
    id: String,
    atributos: HashMap<String, String>,
    creada: SystemTime,
    tocada: SystemTime,
    viva: bool,
    cookie_pendiente: bool,
    sucia: bool,
}

impl Sesion {
    pub fn nueva() -> io::Result<Sesion> {
        let ahora = SystemTime::now();
        Ok(Sesion {
            id: identificador()?,
            atributos: HashMap::new(),
            creada: ahora,
            tocada: ahora,
            viva: true,
            cookie_pendiente: true,
            sucia: true,
        })
    }

    pub fn rescatada(
        id: &str,
        atributos: HashMap<String, String>,
        creada: SystemTime,
        tocada: SystemTime,
    ) -> Sesion {
        Sesion {
            id: id.into(),
            atributos,
            creada,
            tocada,
            viva: true,
            cookie_pendiente: false,
            sucia: false,
        }
    }

    pub fn cambiar_id(&mut self) -> Result<String, &'static str> {
        if !self.viva {
            return Err("una sesión invalidada no se rota");
        }
        self.id = identificador().map_err(|_| "sin fuente aleatoria")?;
        self.cookie_pendiente = true;
        self.sucia = true;
        Ok(self.id.clone())
    }

    pub fn id(&self) -> &str {
        &self.id
    }

    pub fn viva(&self) -> bool {
        self.viva
    }

    pub fn poner(&mut self, clave: &str, valor: &str) -> Result<(), &'static str> {
        if !self.viva {
            return Err("la sesión está invalidada");
        }
        self.atributos.insert(clave.into(), valor.into());
        self.tocada = SystemTime::now();
        self.sucia = true;
        Ok(())
    }

    pub fn leer(&self, clave: &str) -> Result<Option<&String>, &'static str> {
        if !self.viva {
            return Err("la sesión está invalidada");
        }
        Ok(self.atributos.get(clave))
    }

    pub fn invalidar(&mut self) {
        self.viva = false;
        self.atributos.clear();
        self.sucia = true;
    }

    pub fn creada(&self) -> SystemTime {
        self.creada
    }

    pub fn tocada(&self) -> SystemTime {
        self.tocada
    }

    pub fn atributos(&self) -> &HashMap<String, String> {
        &self.atributos
    }

    pub fn sucia(&mut self) -> bool {
        mem::take(&mut self.sucia)
    }

    pub fn cookie_pendiente(&mut self) -> Option<String> {
        mem::take(&mut self.cookie_pendiente).then(|| self.id.clone())
    }
}

pub trait Sesiones: Send + Sync {
    fn recuperar(&self, id: Option<&str>) -> Option<Arc<Mutex<Sesion>>>;
    fn crear(&self) -> io::Result<Arc<Mutex<Sesion>>>;
    fn rotar(&self, sesion: &Arc<Mutex<Sesion>>) -> Result<String, &'static str>;
    fn cuantas(&self) -> usize;

    fn guardar(&self, _sesion: &Arc<Mutex<Sesion>>) {}
}

pub struct Almacen {
    sesiones: RwLock<HashMap<String, Arc<Mutex<Sesion>>>>,
    inactividad: Duration,
    vida_maxima: Option<Duration>,
}

impl Almacen {
    pub fn nuevo(inactividad: Duration, vida_maxima: Option<Duration>) -> Almacen {
        Almacen { sesiones: RwLock::new(HashMap::new()), inactividad, vida_maxima }
    }

    pub fn caducada(&self, creada: SystemTime, tocada: SystemTime) -> bool {
        let desde = |t: SystemTime| SystemTime::now().duration_since(t).unwrap_or_default();
        desde(tocada) > self.inactividad || self.vida_maxima.is_some_and(|v| desde(creada) > v)
    }
}

impl Sesiones for Almacen {
    fn recuperar(&self, id: Option<&str>) -> Option<Arc<Mutex<Sesion>>> {
        let id = id?;
        let s = Arc::clone(self.sesiones.read().ok()?.get(id)?);
        let caduca = {
            let g = s.lock().ok()?;
            !g.viva || g.id != id || self.caducada(g.creada, g.tocada)
        };
        if caduca {
            self.sesiones.write().ok()?.remove(id);
            return None;
        }
        Some(s)
    }

    fn crear(&self) -> io::Result<Arc<Mutex<Sesion>>> {
        let sesion = Sesion::nueva()?;
        let id = sesion.id.clone();
        let s = Arc::new(Mutex::new(sesion));
        self.sesiones
            .write()
            .map_err(|_| io::Error::other("almacén envenenado"))?
            .insert(id, Arc::clone(&s));
        Ok(s)
    }

    fn rotar(&self, sesion: &Arc<Mutex<Sesion>>) -> Result<String, &'static str> {
        let mut g = sesion.lock().map_err(|_| "sesión envenenada")?;
        let mut mapa = self.sesiones.write().map_err(|_| "almacén envenenado")?;
        let viejo = g.id.clone();
        let nuevo = g.cambiar_id()?;
        drop(g);
        mapa.remove(&viejo);
        mapa.insert(nuevo.clone(), Arc::clone(sesion));
        Ok(nuevo)
    }

    fn cuantas(&self) -> usize {
        self.sesiones.read().map_or(0, |m| m.len())
    }
}

pub fn cabecera_cookie(id: &str, seguro: bool) -> String {
    let mut c = format!("cero_sid={id}; Path=/; HttpOnly; SameSite=Lax");
    if seguro {
        c.push_str("; Secure");
    }
    c
}

pub fn id_de_cookie(cabecera: Option<&str>) -> Option<&str> {
    cabecera?
        .split(';')
        .map(str::trim)
        .find_map(|par| par.strip_prefix("cero_sid="))
}
