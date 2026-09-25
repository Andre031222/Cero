//! Observabilidad, según `spec/observabilidad.md` requisitos `OBS-001`–`OBS-023`.
//!
//! Lo que el proceso cuenta de sí mismo: salud, log, métricas y log de acceso.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, RwLock};
use std::time::{Duration, Instant};

// ── Salud · OBS-001 a OBS-007 ───────────────────────────────────────────────────────────────

pub enum Veredicto {
    Bien,
    Mal(String),
}

type Comprobacion = Box<dyn Fn() -> Veredicto + Send + Sync>;

pub struct Salud {
    arranque: Instant,
    comprobaciones: Vec<(String, Comprobacion)>,
    /// OBS-006: un endpoint de salud es alcanzable desde fuera y enumera la infraestructura
    /// interna a quien pregunte. En modo público el código no cambia, el detalle sí.
    pub publico: bool,
}

pub struct Informe {
    pub estado: u16,
    pub cuerpo: String,
}

impl Salud {
    pub fn nueva() -> Salud {
        Salud { arranque: Instant::now(), comprobaciones: Vec::new(), publico: false }
    }

    pub fn comprobacion(
        mut self,
        nombre: &str,
        f: impl Fn() -> Veredicto + Send + Sync + 'static,
    ) -> Salud {
        self.comprobaciones.push((nombre.into(), Box::new(f)));
        self
    }

    /// OBS-001: responde 200 mientras el proceso responda. No admite comprobaciones: si las
    /// aceptara dejaría de medir lo que dice medir, y un supervisor reiniciaría por una base de
    /// datos lenta, cambiando un problema pasajero por una caída.
    pub fn vivo(&self) -> Informe {
        Informe {
            estado: 200,
            cuerpo: format!("{{\"vivo\":true,\"activo_s\":{}}}", self.arranque.elapsed().as_secs()),
        }
    }

    /// OBS-002 a OBS-007.
    pub fn listo(&self) -> Informe {
        let mut fallos: Vec<(String, String)> = Vec::new();
        let mut buenas: Vec<String> = Vec::new();
        for (nombre, f) in &self.comprobaciones {
            // OBS-005: una comprobación que lanza da 503, no 500. Lanzar es una forma de fallar,
            // no un fallo del endpoint. En Rust «lanzar» es `panic!`, y se atrapa aquí.
            let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(f));
            match r {
                Ok(Veredicto::Bien) => buenas.push(nombre.clone()),
                Ok(Veredicto::Mal(motivo)) => fallos.push((nombre.clone(), motivo)),
                Err(_) => fallos.push((nombre.clone(), "la comprobación lanzó".into())),
            }
        }
        if fallos.is_empty() {
            let cuerpo = if self.publico {
                "{\"listo\":true}".to_string() // OBS-007
            } else {
                format!("{{\"listo\":true,\"comprobaciones\":[{}]}}", comillas(&buenas))
            };
            return Informe { estado: 200, cuerpo };
        }
        // OBS-003 y OBS-004: 503 diciendo cuál falló, sin ocultar las que sí van.
        let cuerpo = if self.publico {
            "{\"listo\":false}".to_string() // OBS-006
        } else {
            let detalle: Vec<String> =
                fallos.iter().map(|(n, m)| format!("{{\"nombre\":\"{n}\",\"motivo\":\"{m}\"}}")).collect();
            format!(
                "{{\"listo\":false,\"fallan\":[{}],\"van\":[{}]}}",
                detalle.join(","),
                comillas(&buenas)
            )
        };
        Informe { estado: 503, cuerpo }
    }
}

fn comillas(v: &[String]) -> String {
    v.iter().map(|s| format!("\"{s}\"")).collect::<Vec<_>>().join(",")
}

// ── Registro · OBS-008 a OBS-012 ────────────────────────────────────────────────────────────

#[derive(Debug, PartialEq, PartialOrd, Clone, Copy)]
pub enum Nivel {
    Traza,
    Depuracion,
    Info,
    Aviso,
    Error,
    /// OBS-009: un nivel que calla todo.
    Nada,
}

pub struct Log {
    pub nivel: Nivel,
    pub origen: String,
    lineas: Mutex<Vec<String>>,
}

impl Log {
    pub fn nuevo(origen: &str, nivel: Nivel) -> Log {
        Log { nivel, origen: origen.into(), lineas: Mutex::new(Vec::new()) }
    }

    pub fn escribir(&self, nivel: Nivel, plantilla: &str, valores: &[&str]) {
        if nivel < self.nivel || self.nivel == Nivel::Nada {
            return; // OBS-009
        }
        let linea = format!("{:?} {} {}", nivel, self.origen, interpolar(plantilla, valores));
        self.lineas.lock().expect("log envenenado").push(linea);
    }

    pub fn lineas(&self) -> Vec<String> {
        self.lineas.lock().map(|l| l.clone()).unwrap_or_default()
    }
}

/// OBS-011: con valores de menos se conserva el marcador; con valores de más se ignoran.
/// Interpolar NO puede lanzar nunca: un log que revienta se lleva por delante lo que iba a contar.
pub fn interpolar(plantilla: &str, valores: &[&str]) -> String {
    let mut salida = String::with_capacity(plantilla.len());
    let mut resto = plantilla;
    let mut i = 0;
    while let Some(p) = resto.find("{}") {
        salida.push_str(&resto[..p]);
        match valores.get(i) {
            Some(v) => salida.push_str(v),
            None => salida.push_str("{}"),
        }
        i += 1;
        resto = &resto[p + 2..];
    }
    salida.push_str(resto);
    salida
}

// ── Métricas · OBS-013 a OBS-018 ────────────────────────────────────────────────────────────

#[derive(Default)]
struct Cuenta {
    peticiones: u64,
    errores: u64,
    micros: Vec<u64>,
}

pub struct Metricas {
    arranque: Instant,
    /// OBS-014: la clave es el **patrón** de ruta, no la URL. Con la URL, `/usuarios/{id}` genera
    /// tantas series como identificadores existan: cardinalidad sin acotar desde entrada externa,
    /// la misma familia que el hallazgo del limitador pero contra el sistema de métricas.
    por_patron: RwLock<HashMap<String, Cuenta>>,
    total: AtomicU64,
    ignoradas: RwLock<Vec<String>>,
}

impl Metricas {
    pub fn nuevas() -> Metricas {
        Metricas {
            arranque: Instant::now(),
            por_patron: RwLock::new(HashMap::new()),
            total: AtomicU64::new(0),
            ignoradas: RwLock::new(Vec::new()),
        }
    }

    pub fn ignorar(&self, patron: &str) {
        self.ignoradas.write().expect("envenenado").push(patron.into());
    }

    pub fn anotar(&self, patron: &str, estado: u16, tardo: Duration) {
        // OBS-017: las ignoradas no se cuentan.
        if self.ignoradas.read().expect("envenenado").iter().any(|p| p == patron) {
            return;
        }
        self.total.fetch_add(1, Ordering::Relaxed);
        let mut mapa = self.por_patron.write().expect("envenenado");
        let c = mapa.entry(patron.into()).or_default();
        c.peticiones += 1;
        // OBS-016: un 404 cuenta como error.
        if estado >= 400 {
            c.errores += 1;
        }
        c.micros.push(tardo.as_micros() as u64);
    }

    pub fn total(&self) -> u64 {
        self.total.load(Ordering::Relaxed)
    }

    pub fn peticiones(&self, patron: &str) -> u64 {
        self.por_patron.read().map(|m| m.get(patron).map_or(0, |c| c.peticiones)).unwrap_or(0)
    }

    pub fn errores(&self, patron: &str) -> u64 {
        self.por_patron.read().map(|m| m.get(patron).map_or(0, |c| c.errores)).unwrap_or(0)
    }

    /// OBS-015: percentiles, no solo la media. La media esconde la cola, que es donde vive el
    /// usuario que se queja.
    pub fn percentil(&self, patron: &str, p: f64) -> Option<u64> {
        let mapa = self.por_patron.read().ok()?;
        let mut v = mapa.get(patron)?.micros.clone();
        if v.is_empty() {
            return None;
        }
        v.sort_unstable();
        let i = ((v.len() as f64 - 1.0) * p).round() as usize;
        Some(v[i])
    }

    pub fn patrones(&self) -> usize {
        self.por_patron.read().map(|m| m.len()).unwrap_or(0)
    }

    /// OBS-018: una exposición legible por máquina, con el total y el detalle por ruta.
    pub fn json(&self) -> String {
        let mapa = self.por_patron.read().expect("envenenado");
        let rutas: Vec<String> = mapa
            .iter()
            .map(|(p, c)| {
                format!("{{\"ruta\":\"{p}\",\"peticiones\":{},\"errores\":{}}}", c.peticiones, c.errores)
            })
            .collect();
        format!(
            "{{\"total\":{},\"activo_s\":{},\"rutas\":[{}]}}",
            self.total(),
            self.arranque.elapsed().as_secs(),
            rutas.join(",")
        )
    }
}

// ── Log de acceso · OBS-019 a OBS-023 ───────────────────────────────────────────────────────

/// OBS-019 a OBS-021. El usuario sin identificar va con una marca explícita y no con un hueco:
/// un campo vacío en una línea separada por espacios corre las columnas siguientes.
pub fn linea_acceso(
    metodo: &str,
    destino: &str,
    estado: u16,
    usuario: Option<&str>,
    tardo: Duration,
) -> String {
    format!(
        "{} {} {} {} {}ms",
        metodo,
        destino,
        estado,
        usuario.unwrap_or("-"),
        tardo.as_millis()
    )
}
