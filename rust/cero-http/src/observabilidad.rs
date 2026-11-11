use std::any::type_name;
use std::collections::HashMap;
use std::fmt::Display;
use std::panic::{self, AssertUnwindSafe};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, RwLock};
use std::time::{Duration, Instant};

pub enum Veredicto {
    Bien,
    Mal(String),
}

type Comprobacion = Box<dyn Fn() -> Veredicto + Send + Sync>;

pub struct Salud {
    arranque: Instant,
    comprobaciones: Vec<(String, Comprobacion)>,
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

    pub fn vivo(&self) -> Informe {
        Informe {
            estado: 200,
            cuerpo: format!("{{\"vivo\":true,\"activo_s\":{}}}", self.arranque.elapsed().as_secs()),
        }
    }

    pub fn listo(&self) -> Informe {
        let mut fallos: Vec<(String, String)> = Vec::new();
        let mut buenas: Vec<String> = Vec::new();
        for (nombre, f) in &self.comprobaciones {
            match panic::catch_unwind(AssertUnwindSafe(f)) {
                Ok(Veredicto::Bien) => buenas.push(nombre.clone()),
                Ok(Veredicto::Mal(motivo)) => fallos.push((nombre.clone(), motivo)),
                Err(_) => fallos.push((nombre.clone(), "la comprobación lanzó".into())),
            }
        }
        if fallos.is_empty() {
            let cuerpo = if self.publico {
                "{\"listo\":true}".to_string()
            } else {
                format!("{{\"listo\":true,\"comprobaciones\":[{}]}}", comillas(&buenas))
            };
            return Informe { estado: 200, cuerpo };
        }
        let cuerpo = if self.publico {
            "{\"listo\":false}".to_string()
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

#[derive(Debug, PartialEq, PartialOrd, Clone, Copy)]
pub enum Nivel {
    Traza,
    Depuracion,
    Info,
    Aviso,
    Error,
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
            return;
        }
        let linea = format!("{:?} {} {}", nivel, self.origen, interpolar(plantilla, valores));
        self.lineas.lock().expect("log envenenado").push(linea);
    }

    pub fn con_error<E: Display>(&self, plantilla: &str, valores: &[&str], error: &E) {
        let tipo = type_name::<E>().rsplit("::").next().unwrap_or("Error");
        self.escribir(Nivel::Error, &format!("{plantilla}: {tipo}: {error}"), valores);
    }

    pub fn lineas(&self) -> Vec<String> {
        self.lineas.lock().map(|l| l.clone()).unwrap_or_default()
    }
}

pub fn interpolar(plantilla: &str, valores: &[&str]) -> String {
    let mut salida = String::with_capacity(plantilla.len());
    let mut resto = plantilla;
    let mut i = 0;
    while let Some(p) = resto.find("{}") {
        salida.push_str(&resto[..p]);
        salida.push_str(valores.get(i).copied().unwrap_or("{}"));
        i += 1;
        resto = &resto[p + 2..];
    }
    salida.push_str(resto);
    salida
}

#[derive(Default)]
struct Cuenta {
    peticiones: u64,
    errores: u64,
    micros: Vec<u64>,
}

pub struct Metricas {
    arranque: Instant,
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
        if self.ignoradas.read().expect("envenenado").iter().any(|p| p == patron) {
            return;
        }
        self.total.fetch_add(1, Ordering::Relaxed);
        let mut mapa = self.por_patron.write().expect("envenenado");
        let c = mapa.entry(patron.into()).or_default();
        c.peticiones += 1;
        if estado >= 400 {
            c.errores += 1;
        }
        c.micros.push(tardo.as_micros() as u64);
    }

    pub fn total(&self) -> u64 {
        self.total.load(Ordering::Relaxed)
    }

    pub fn peticiones(&self, patron: &str) -> u64 {
        self.por_patron.read().map_or(0, |m| m.get(patron).map_or(0, |c| c.peticiones))
    }

    pub fn errores(&self, patron: &str) -> u64 {
        self.por_patron.read().map_or(0, |m| m.get(patron).map_or(0, |c| c.errores))
    }

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
        self.por_patron.read().map_or(0, |m| m.len())
    }

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

pub fn linea_acceso(
    metodo: &str,
    destino: &str,
    estado: u16,
    usuario: Option<&str>,
    tardo: Duration,
) -> String {
    format!("{metodo} {destino} {estado} {} {}ms", usuario.unwrap_or("-"), tardo.as_millis())
}
