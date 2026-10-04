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
use std::time::{Duration, SystemTime};

/// SES-002: al menos 40 caracteres de una fuente apta para criptografía.
///
/// Java tiene `SecureRandom` en la plataforma. `std` de Rust no trae generador criptográfico, y
/// traerlo sería una dependencia. Se lee de la fuente del sistema operativo, que es lo que hace
/// `SecureRandom` por debajo: el requisito habla de la propiedad, no del nombre de la clase.
pub(crate) fn identificador() -> std::io::Result<String> {
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
    /// Reloj de pared y no `Instant`. `Instant` es monótono y **local al proceso**: no se puede
    /// guardar ni comparar con el de otro, así que una sesión que vive en una tabla y la leen dos
    /// instancias no puede medir su edad con él. Es `SES-012` y `SES-013` decidiendo el tipo.
    creada: SystemTime,
    tocada: SystemTime,
    /// SES-004: invalidar deja la sesión inutilizable, no la recrea en silencio.
    viva: bool,
    /// SES-008: la cookie se emite una vez. Que esto sea `bool` y no un cálculo es deliberado:
    /// SES-011 dice que consultarlo **no es una lectura pura**.
    cookie_pendiente: bool,
    /// Si cambió desde la última vez que se guardó. Un almacén que escribe en cada respuesta
    /// escribe también en cada `GET` que no tocó nada, y eso son dos viajes a la base por visita.
    sucia: bool,
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

    /// Lo que un almacén necesita saber para no escribir de más, y para dejar de deberlo cuando ya
    /// escribió. Como `cookie_pendiente`, consultarlo **consume**: toma `&mut`.
    pub fn sucia(&mut self) -> bool {
        std::mem::take(&mut self.sucia)
    }

    /// Reconstruye una sesión que un almacén había guardado.
    ///
    /// No nace sucia ni con cookie pendiente: ya estaba guardada y el cliente ya tiene su cookie,
    /// porque es la que usó para llegar hasta aquí.
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

/// Lo que el servidor le pide a un almacén de sesiones, sea el de memoria o uno en una tabla.
///
/// Que sea un rasgo es lo que hace cumplible `SES-013`: el nombre de la tabla es asunto de quien
/// lo implementa, no del contrato. `cero-data` trae la implementación sobre SQL, igual que Java la
/// trae en su `cero-data` con `JdbcSessions`.
pub trait Sesiones: Send + Sync {
    fn recuperar(&self, id: Option<&str>) -> Option<Arc<Mutex<Sesion>>>;
    fn crear(&self) -> std::io::Result<Arc<Mutex<Sesion>>>;
    fn rotar(&self, sesion: &Arc<Mutex<Sesion>>) -> Result<String, &'static str>;
    fn cuantas(&self) -> usize;

    /// Se llama **una vez por respuesta**, en el mismo sitio que emite la cookie.
    ///
    /// Que sea un solo punto no es estilo: `SES-010` nació de tener dos salidas y hacer el trabajo
    /// en una. Un almacén en memoria no tiene nada que hacer aquí porque la sesión que mutó la
    /// acción **es** la que él guarda; uno en una tabla, todo.
    fn guardar(&self, _sesion: &Arc<Mutex<Sesion>>) {}
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

    /// Si una sesión con estas marcas de tiempo ya caducó, por inactividad o por vida máxima.
    /// Lo usan los dos almacenes, y por eso vive aquí y no dentro de uno.
    pub fn caducada(&self, creada: SystemTime, tocada: SystemTime) -> bool {
        let desde = |t: SystemTime| SystemTime::now().duration_since(t).unwrap_or_default();
        desde(tocada) > self.inactividad || self.vida_maxima.is_some_and(|v| desde(creada) > v)
    }

    pub fn inactividad(&self) -> Duration {
        self.inactividad
    }
}

impl Sesiones for Almacen {
    /// SES-001: sin cookie no se recupera nada. Devuelve `None` en vez de crear una sesión: crear
    /// en la lectura es lo que convierte un rastreador en un generador de sesiones huérfanas.
    fn recuperar(&self, id: Option<&str>) -> Option<Arc<Mutex<Sesion>>> {
        let id = id?;
        let mapa = self.sesiones.read().ok()?;
        let s = mapa.get(id)?.clone();
        let caduca = {
            let g = s.lock().ok()?;
            !g.viva || self.caducada(g.creada, g.tocada)
        };
        if caduca {
            drop(mapa);
            self.sesiones.write().ok()?.remove(id);
            return None;
        }
        Some(s)
    }

    fn crear(&self) -> std::io::Result<Arc<Mutex<Sesion>>> {
        let ahora = SystemTime::now();
        let s = Arc::new(Mutex::new(Sesion {
            id: identificador()?,
            atributos: HashMap::new(),
            creada: ahora,
            tocada: ahora,
            viva: true,
            cookie_pendiente: true,
            sucia: true,
        }));
        let id = s.lock().expect("recién creada").id.clone();
        self.sesiones.write().expect("almacén envenenado").insert(id, s.clone());
        Ok(s)
    }

    /// SES-005: rota el identificador conservando los atributos y obliga a reemitir la cookie.
    /// SES-006: una sesión invalidada no se rota.
    fn rotar(&self, sesion: &Arc<Mutex<Sesion>>) -> Result<String, &'static str> {
        let mut g = sesion.lock().map_err(|_| "sesión envenenada")?;
        if !g.viva {
            return Err("una sesión invalidada no se rota");
        }
        let viejo = g.id.clone();
        let nuevo = identificador().map_err(|_| "sin fuente aleatoria")?;
        g.id = nuevo.clone();
        g.cookie_pendiente = true;
        g.sucia = true;
        drop(g);
        let mut mapa = self.sesiones.write().map_err(|_| "almacén envenenado")?;
        mapa.remove(&viejo);
        mapa.insert(nuevo.clone(), sesion.clone());
        Ok(nuevo)
    }

    fn cuantas(&self) -> usize {
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
