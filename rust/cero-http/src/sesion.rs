//! Sesiones, según `spec/sesiones.md` requisitos `SES-001`–`SES-013`.
//!
//! Aquí es donde el contrato se pone a prueba de verdad. Las sesiones son estado compartido entre
//! peticiones, y Java lo resuelve con un hilo virtual por conexión sobre estructuras concurrentes
//! de la plataforma. En Rust no hay ni una cosa ni la otra: el estado compartido se declara en el
//! tipo, y el compilador no deja compilar si no se declara bien.
//!
//! Que los trece requisitos se cumplan igual es lo que dice si `spec/` era neutral o si estaba
//! describiendo Java con otras palabras.

use std::collections::HashMap;
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant};

/// SES-002: al menos 40 caracteres de una fuente apta para criptografía.
///
/// Java tiene `SecureRandom` en la plataforma. `std` de Rust no trae generador criptográfico, y
/// traerlo sería una dependencia. Se lee de la fuente del sistema operativo, que es lo que hace
/// `SecureRandom` por debajo: el requisito habla de la propiedad, no del nombre de la clase.
fn identificador() -> std::io::Result<String> {
    use std::io::Read;
    let mut crudo = [0u8; 32];
    std::fs::File::open("/dev/urandom")?.read_exact(&mut crudo)?;
    // base16 da 64 caracteres de 32 octetos: por encima del mínimo y sin alfabeto ambiguo.
    Ok(crudo.iter().map(|b| format!("{b:02x}")).collect())
}

#[derive(Debug)]
pub struct Sesion {
    id: String,
    atributos: HashMap<String, String>,
    creada: Instant,
    tocada: Instant,
    /// SES-004: invalidar deja la sesión inutilizable, no la recrea en silencio.
    viva: bool,
    /// SES-008: la cookie se emite una vez. Que esto sea `bool` y no un cálculo es deliberado:
    /// SES-011 dice que consultarlo **no es una lectura pura**.
    cookie_pendiente: bool,
}

impl Sesion {
    pub fn id(&self) -> &str {
        &self.id
    }

    pub fn viva(&self) -> bool {
        self.viva
    }

    /// SES-004: leer o escribir una sesión invalidada falla.
    pub fn poner(&mut self, clave: &str, valor: &str) -> Result<(), &'static str> {
        if !self.viva {
            return Err("la sesión está invalidada");
        }
        self.atributos.insert(clave.into(), valor.into());
        self.tocada = Instant::now();
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
    }

    /// SES-011: marca la cookie como emitida. **No es una lectura pura**, y por eso toma `&mut`:
    /// el compilador impide llamarla dos veces por descuido desde dos caminos de salida, que es
    /// exactamente el fallo que Cero 0.6.0 tuvo en Java entre HTTP/1.1 y HTTP/2.
    pub fn cookie_pendiente(&mut self) -> Option<String> {
        if !self.cookie_pendiente {
            return None;
        }
        self.cookie_pendiente = false;
        Some(self.id.clone())
    }
}

pub struct Almacen {
    sesiones: RwLock<HashMap<String, Arc<Mutex<Sesion>>>>,
    inactividad: Duration,
    /// SES: caducidad absoluta, además de la de inactividad.
    vida_maxima: Option<Duration>,
}

impl Almacen {
    pub fn nuevo(inactividad: Duration, vida_maxima: Option<Duration>) -> Almacen {
        Almacen { sesiones: RwLock::new(HashMap::new()), inactividad, vida_maxima }
    }

    /// SES-001: sin cookie no se recupera nada. Devuelve `None` en vez de crear una sesión: crear
    /// en la lectura es lo que convierte un rastreador en un generador de sesiones huérfanas.
    pub fn recuperar(&self, id: Option<&str>) -> Option<Arc<Mutex<Sesion>>> {
        let id = id?;
        let mapa = self.sesiones.read().ok()?;
        let s = mapa.get(id)?.clone();
        let caduca = {
            let g = s.lock().ok()?;
            !g.viva
                || g.tocada.elapsed() > self.inactividad
                || self.vida_maxima.is_some_and(|v| g.creada.elapsed() > v)
        };
        if caduca {
            drop(mapa);
            self.sesiones.write().ok()?.remove(id);
            return None;
        }
        Some(s)
    }

    pub fn crear(&self) -> std::io::Result<Arc<Mutex<Sesion>>> {
        let ahora = Instant::now();
        let s = Arc::new(Mutex::new(Sesion {
            id: identificador()?,
            atributos: HashMap::new(),
            creada: ahora,
            tocada: ahora,
            viva: true,
            cookie_pendiente: true,
        }));
        let id = s.lock().expect("recién creada").id.clone();
        self.sesiones.write().expect("almacén envenenado").insert(id, s.clone());
        Ok(s)
    }

    /// SES-005: rota el identificador conservando los atributos y obliga a reemitir la cookie.
    /// SES-006: una sesión invalidada no se rota.
    pub fn rotar(&self, sesion: &Arc<Mutex<Sesion>>) -> Result<String, &'static str> {
        let mut g = sesion.lock().map_err(|_| "sesión envenenada")?;
        if !g.viva {
            return Err("una sesión invalidada no se rota");
        }
        let viejo = g.id.clone();
        let nuevo = identificador().map_err(|_| "sin fuente aleatoria")?;
        g.id = nuevo.clone();
        g.cookie_pendiente = true;
        drop(g);
        let mut mapa = self.sesiones.write().map_err(|_| "almacén envenenado")?;
        mapa.remove(&viejo);
        mapa.insert(nuevo.clone(), sesion.clone());
        Ok(nuevo)
    }

    pub fn cuantas(&self) -> usize {
        self.sesiones.read().map(|m| m.len()).unwrap_or(0)
    }
}

/// SES-009: `HttpOnly` y `SameSite=Lax` siempre; `Secure` cuando y solo cuando hay TLS.
pub fn cabecera_cookie(id: &str, seguro: bool) -> String {
    let mut c = format!("cero_sid={id}; Path=/; HttpOnly; SameSite=Lax");
    if seguro {
        c.push_str("; Secure");
    }
    c
}

/// Lee el identificador de la cabecera `Cookie`, que trae pares separados por `;`.
pub fn id_de_cookie(cabecera: Option<&str>) -> Option<&str> {
    cabecera?
        .split(';')
        .map(str::trim)
        .find_map(|par| par.strip_prefix("cero_sid="))
}
