use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use cero_http::sesion::{Almacen, Sesion, Sesiones};
use cero_http::Json;

use crate::{Fila, Fuente, Repositorio, Valor};

pub struct AlmacenSql {
    fuente: Arc<dyn Fuente>,
    tabla: String,
    reglas: Almacen,
}

impl AlmacenSql {
    pub fn nuevo(
        fuente: Arc<dyn Fuente>,
        tabla: &str,
        inactividad: Duration,
        vida_maxima: Option<Duration>,
    ) -> crate::Resultado<AlmacenSql> {
        Repositorio::nuevo(&*fuente, tabla, COLUMNA_ID)?;
        Ok(AlmacenSql {
            fuente,
            tabla: tabla.into(),
            reglas: Almacen::nuevo(inactividad, vida_maxima),
        })
    }

    pub fn tabla(&self) -> &str {
        &self.tabla
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

    fn repositorio(&self) -> crate::Resultado<Repositorio<'_>> {
        Repositorio::nuevo(&*self.fuente, &self.tabla, COLUMNA_ID)
    }

    fn fila(&self, sesion: &Sesion) -> Fila {
        Fila::de(vec![
            (COLUMNA_ID, Valor::Texto(sesion.id().into())),
            (COLUMNA_DATOS, Valor::Texto(a_json(sesion.atributos()))),
            (COLUMNA_CREADA, Valor::Entero(en_segundos(sesion.creada()))),
            (COLUMNA_TOCADA, Valor::Entero(en_segundos(sesion.tocada()))),
        ])
    }
}

const COLUMNA_ID: &str = "id";
const COLUMNA_DATOS: &str = "datos";
const COLUMNA_CREADA: &str = "creada";
const COLUMNA_TOCADA: &str = "tocada";

impl Sesiones for AlmacenSql {
    fn recuperar(&self, id: Option<&str>) -> Option<Arc<Mutex<Sesion>>> {
        let id = id?;
        let fila = self.repositorio().ok()?.por_clave(Valor::Texto(id.into())).ok()??;
        let creada = desde_segundos(fila.entero(COLUMNA_CREADA)?);
        let tocada = desde_segundos(fila.entero(COLUMNA_TOCADA)?);
        if self.reglas.caducada(creada, tocada) {
            let _ = self.repositorio().ok()?.borrar(Valor::Texto(id.into()));
            return None;
        }
        let atributos = de_json(&fila.texto(COLUMNA_DATOS)?);
        Some(Arc::new(Mutex::new(Sesion::rescatada(id, atributos, creada, tocada))))
    }

    fn crear(&self) -> std::io::Result<Arc<Mutex<Sesion>>> {
        let sesion = self.reglas.crear()?;
        self.guardar(&sesion);
        Ok(sesion)
    }

    fn rotar(&self, sesion: &Arc<Mutex<Sesion>>) -> Result<String, &'static str> {
        let viejo = sesion.lock().map_err(|_| "sesión envenenada")?.id().to_string();
        let nuevo = self.reglas.rotar(sesion)?;
        self.guardar(sesion);
        let _ = self
            .repositorio()
            .map_err(|_| "la tabla no está disponible")?
            .borrar(Valor::Texto(viejo));
        Ok(nuevo)
    }

    fn cuantas(&self) -> usize {
        self.repositorio()
            .and_then(|r| r.todos(u32::MAX))
            .map(|f| f.len())
            .unwrap_or(0)
    }

    fn guardar(&self, sesion: &Arc<Mutex<Sesion>>) {
        let Ok(mut g) = sesion.lock() else { return };
        if !g.sucia() {
            return;
        }
        if !g.viva() {
            let id = g.id().to_string();
            drop(g);
            if let Ok(r) = self.repositorio() {
                let _ = r.borrar(Valor::Texto(id));
            }
            return;
        }
        let fila = self.fila(&g);
        drop(g);
        if let Ok(r) = self.repositorio() {
            let _ = r.guardar(&fila);
        }
    }
}

fn a_json(atributos: &HashMap<String, String>) -> String {
    let pares = atributos.iter().map(|(k, v)| (k.as_str(), Json::Texto(v.clone())));
    Json::objeto(pares.collect()).escribir()
}

fn de_json(texto: &str) -> HashMap<String, String> {
    let Ok(Json::Objeto(mapa)) = cero_http::json::leer(texto) else {
        return HashMap::new();
    };
    mapa.into_iter()
        .filter_map(|(k, v)| v.texto().map(|t| (k, t.to_string())))
        .collect()
}

fn en_segundos(t: SystemTime) -> i64 {
    t.duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

fn desde_segundos(s: i64) -> SystemTime {
    UNIX_EPOCH + Duration::from_secs(s.max(0) as u64)
}
