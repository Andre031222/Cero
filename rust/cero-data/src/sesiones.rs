use std::collections::HashMap;
use std::io;
use std::sync::{Arc, Mutex, Weak};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use cero_http::Json;
use cero_http::json;
use cero_http::sesion::{Almacen, Sesion, Sesiones};

use crate::{Fila, Fuente, Repositorio, Resultado, Valor};

const COLUMNA_ID: &str = "id";
const COLUMNA_DATOS: &str = "datos";
const COLUMNA_CREADA: &str = "creada";
const COLUMNA_TOCADA: &str = "tocada";

pub struct AlmacenSql {
    fuente: Arc<dyn Fuente>,
    tabla: String,
    reglas: Almacen,
    vivas: Mutex<HashMap<String, Weak<Mutex<Sesion>>>>,
}

impl AlmacenSql {
    pub fn nuevo(
        fuente: Arc<dyn Fuente>,
        tabla: &str,
        inactividad: Duration,
        vida_maxima: Option<Duration>,
    ) -> Resultado<AlmacenSql> {
        Repositorio::nuevo(&*fuente, tabla, COLUMNA_ID)?;
        Ok(AlmacenSql {
            fuente,
            tabla: tabla.into(),
            reglas: Almacen::nuevo(inactividad, vida_maxima),
            vivas: Mutex::default(),
        })
    }

    pub fn tabla(&self) -> &str {
        &self.tabla
    }

    pub fn abiertas(&self) -> usize {
        self.vivas.lock().map_or(0, |v| v.len())
    }

    pub fn esquema(tabla: &str) -> String {
        format!(
            "CREATE TABLE IF NOT EXISTS {tabla} (\n  \
               {COLUMNA_ID} VARCHAR(128) PRIMARY KEY,\n  \
               {COLUMNA_DATOS} TEXT NOT NULL,\n  \
               {COLUMNA_CREADA} BIGINT NOT NULL,\n  \
               {COLUMNA_TOCADA} BIGINT NOT NULL\n)"
        )
    }

    fn repositorio(&self) -> Resultado<Repositorio<'_>> {
        Repositorio::nuevo(&*self.fuente, &self.tabla, COLUMNA_ID)
    }

    fn borrar(&self, id: &str) {
        if let Ok(r) = self.repositorio() {
            let _ = r.borrar(Valor::Texto(id.into()));
        }
    }

    fn viva(&self, id: &str) -> Option<Arc<Mutex<Sesion>>> {
        self.vivas.lock().ok()?.get(id)?.upgrade()
    }

    fn registrar(&self, id: &str, sesion: Arc<Mutex<Sesion>>) -> Option<Arc<Mutex<Sesion>>> {
        let mut vivas = self.vivas.lock().ok()?;
        if let Some(primera) = vivas.get(id).and_then(Weak::upgrade) {
            return Some(primera);
        }
        vivas.retain(|_, s| s.strong_count() > 0);
        vivas.insert(id.into(), Arc::downgrade(&sesion));
        Some(sesion)
    }

    fn cargar(&self, id: &str) -> Option<Arc<Mutex<Sesion>>> {
        let fila = self.repositorio().ok()?.por_clave(Valor::Texto(id.into())).ok()??;
        let creada = desde_segundos(fila.entero(COLUMNA_CREADA)?);
        let tocada = desde_segundos(fila.entero(COLUMNA_TOCADA)?);
        let atributos = de_json(&fila.texto(COLUMNA_DATOS)?);
        let sesion = Sesion::rescatada(id, atributos, creada, tocada);
        self.registrar(id, Arc::new(Mutex::new(sesion)))
    }
}

impl Sesiones for AlmacenSql {
    fn recuperar(&self, id: Option<&str>) -> Option<Arc<Mutex<Sesion>>> {
        let id = id?;
        let sesion = self.viva(id).or_else(|| self.cargar(id))?;
        let mut g = sesion.lock().ok()?;
        if g.id() != id {
            return None;
        }
        if g.viva() && !self.reglas.caducada(g.creada(), g.tocada()) {
            drop(g);
            return Some(sesion);
        }
        g.invalidar();
        drop(g);
        if let Ok(mut vivas) = self.vivas.lock() {
            vivas.remove(id);
        }
        self.borrar(id);
        None
    }

    fn crear(&self) -> io::Result<Arc<Mutex<Sesion>>> {
        let sesion = Sesion::nueva()?;
        let id = sesion.id().to_string();
        let sesion = self
            .registrar(&id, Arc::new(Mutex::new(sesion)))
            .ok_or_else(|| io::Error::other("registro envenenado"))?;
        self.guardar(&sesion);
        Ok(sesion)
    }

    fn rotar(&self, sesion: &Arc<Mutex<Sesion>>) -> Result<String, &'static str> {
        let mut g = sesion.lock().map_err(|_| "sesión envenenada")?;
        let mut vivas = self.vivas.lock().map_err(|_| "registro envenenado")?;
        let viejo = g.id().to_string();
        let nuevo = g.cambiar_id()?;
        vivas.remove(&viejo);
        vivas.insert(nuevo.clone(), Arc::downgrade(sesion));
        drop(vivas);
        drop(g);
        self.guardar(sesion);
        let r = self.repositorio().map_err(|_| "la tabla no está disponible")?;
        let _ = r.borrar(Valor::Texto(viejo));
        Ok(nuevo)
    }

    fn cuantas(&self) -> usize {
        self.repositorio()
            .and_then(|r| r.todos(u32::MAX))
            .map_or(0, |f| f.len())
    }

    fn guardar(&self, sesion: &Arc<Mutex<Sesion>>) {
        let Ok(mut g) = sesion.lock() else { return };
        if !g.sucia() {
            return;
        }
        let Ok(r) = self.repositorio() else { return };
        if g.viva() {
            let _ = r.guardar(&fila(&g));
        } else {
            let _ = r.borrar(Valor::Texto(g.id().into()));
        }
    }
}

fn fila(sesion: &Sesion) -> Fila {
    Fila::de(vec![
        (COLUMNA_ID, Valor::Texto(sesion.id().into())),
        (COLUMNA_DATOS, Valor::Texto(a_json(sesion.atributos()))),
        (COLUMNA_CREADA, Valor::Entero(en_segundos(sesion.creada()))),
        (COLUMNA_TOCADA, Valor::Entero(en_segundos(sesion.tocada()))),
    ])
}

fn a_json(atributos: &HashMap<String, String>) -> String {
    let pares = atributos.iter().map(|(k, v)| (k.as_str(), Json::Texto(v.clone())));
    Json::objeto(pares.collect()).escribir()
}

fn de_json(texto: &str) -> HashMap<String, String> {
    let Ok(Json::Objeto(mapa)) = json::leer(texto) else {
        return HashMap::new();
    };
    mapa.into_iter()
        .filter_map(|(k, v)| v.texto().map(|t| (k, t.to_string())))
        .collect()
}

fn en_segundos(t: SystemTime) -> i64 {
    t.duration_since(UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_secs()).unwrap_or(i64::MAX))
}

fn desde_segundos(s: i64) -> SystemTime {
    UNIX_EPOCH + Duration::from_secs(u64::try_from(s).unwrap_or(0))
}
