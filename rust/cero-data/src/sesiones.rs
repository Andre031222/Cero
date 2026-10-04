//! Sesiones en una tabla: `spec/sesiones.md`, `SES-012` y `SES-013`.
//!
//! El almacén en memoria de `cero-http` sirve para un proceso. En cuanto hay dos instancias detrás
//! de un balanceador, la sesión que abrió una no existe para la otra y el usuario se encuentra
//! fuera cada dos peticiones. Esto lo arregla, y también que un despliegue no eche a nadie.
//!
//! Vive aquí y no en `cero-http` por la misma razón que `JdbcSessions` vive en el `cero-data` de
//! Java: la capa HTTP define el contrato —el rasgo `Sesiones`— y quien sabe de bases de datos lo
//! implementa. Al revés metería SQL dentro del servidor.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use cero_http::sesion::{Almacen, Sesion, Sesiones};
use cero_http::Json;

use crate::{Fila, Fuente, Repositorio, Valor};

pub struct AlmacenSql {
    fuente: Arc<dyn Fuente>,
    tabla: String,
    /// La caducidad y el identificador se delegan: son reglas del contrato de sesiones, no de la
    /// base de datos, y reescribirlas aquí sería tener dos sitios donde pueden discrepar.
    reglas: Almacen,
}

impl AlmacenSql {
    /// `SES-013`: el nombre de la tabla se da al construir y **no hay valor por omisión**.
    ///
    /// Exigir una tabla llamada `sesiones` obliga a quien ya tiene un esquema a renombrar lo suyo
    /// para encajar en el framework, que es la relación al revés. El nombre se valida porque un
    /// identificador no puede ir parametrizado en SQL: si viene de fuera y no se comprueba, es
    /// inyección — y eso lo hace `Repositorio` al construirse.
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

    /// El DDL de la tabla, para que la migración no haya que adivinarla.
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
    /// `SES-001` sigue valiendo: sin cookie no se busca nada, y lo que no está no se crea.
    fn recuperar(&self, id: Option<&str>) -> Option<Arc<Mutex<Sesion>>> {
        let id = id?;
        let fila = self.repositorio().ok()?.por_clave(Valor::Texto(id.into())).ok()??;
        let creada = desde_segundos(fila.entero(COLUMNA_CREADA)?);
        let tocada = desde_segundos(fila.entero(COLUMNA_TOCADA)?);
        if self.reglas.caducada(creada, tocada) {
            // Caducada se borra al tocarla y no por un barrido: así no hace falta un hilo que
            // limpie, y la fila muerta no puede volver a autenticar a nadie por mucho que quede.
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

    /// `SES-005`: el identificador cambia y los atributos se conservan. En una tabla eso son dos
    /// pasos, y el orden importa: primero se escribe la fila nueva y **después** se borra la
    /// vieja. Al revés, un fallo entre los dos pasos pierde la sesión; así, lo peor que deja es
    /// una fila de sobra que caduca sola.
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

    /// Solo si cambió. Guardar en cada respuesta serían dos viajes a la base por cada `GET` que no
    /// tocó nada, y eso convierte el almacén compartido en el cuello de botella del servidor.
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

/// Los atributos van como JSON y no en columnas: una sesión guarda lo que la aplicación quiera, y
/// una columna por clave obligaría a migrar el esquema cada vez que alguien guarda algo nuevo.
fn a_json(atributos: &HashMap<String, String>) -> String {
    let pares = atributos.iter().map(|(k, v)| (k.as_str(), Json::Texto(v.clone())));
    Json::objeto(pares.collect()).escribir()
}

/// Lo que no se entiende se devuelve vacío y no revienta: una fila corrupta deja a alguien sin
/// sesión, pero tirar el servidor por ella deja a todos sin servicio.
fn de_json(texto: &str) -> HashMap<String, String> {
    let Ok(Json::Objeto(mapa)) = cero_http::json::leer(texto) else {
        return HashMap::new();
    };
    mapa.into_iter()
        .filter_map(|(k, v)| v.texto().map(|t| (k, t.to_string())))
        .collect()
}

/// Segundos desde la época. La fecha se guarda como entero y no como el tipo temporal del motor
/// porque ahí cada uno tiene el suyo, y el contrato no puede depender de cuál.
fn en_segundos(t: SystemTime) -> i64 {
    t.duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

fn desde_segundos(s: i64) -> SystemTime {
    UNIX_EPOCH + Duration::from_secs(s.max(0) as u64)
}
