use std::collections::HashMap;
use std::sync::{Condvar, Mutex};
use std::time::Instant;

const TOPE: i64 = 0x7fff_ffff;

struct Ventanas {
    conexion: i64,
    flujos: HashMap<u32, i64>,
    inicial: i64,
    abierto: bool,
}

pub struct Credito {
    ventanas: Mutex<Ventanas>,
    hay: Condvar,
}

impl Credito {
    pub fn nuevo(inicial: u32) -> Credito {
        let ventanas = Ventanas {
            conexion: 65_535,
            flujos: HashMap::new(),
            inicial: inicial as i64,
            abierto: true,
        };
        Credito { ventanas: Mutex::new(ventanas), hay: Condvar::new() }
    }

    pub fn abrir(&self, flujo: u32) {
        let mut v = self.ventanas.lock().unwrap();
        let inicial = v.inicial;
        v.flujos.insert(flujo, inicial);
    }

    pub fn anular(&self, flujo: u32) {
        self.ventanas.lock().unwrap().flujos.remove(&flujo);
        self.hay.notify_all();
    }

    pub fn ampliar(&self, flujo: u32, cuanto: i64) -> bool {
        let mut v = self.ventanas.lock().unwrap();
        let ventana = if flujo == 0 { &mut v.conexion } else {
            let Some(w) = v.flujos.get_mut(&flujo) else { return true };
            w
        };
        if *ventana + cuanto > TOPE {
            return false;
        }
        *ventana += cuanto;
        self.hay.notify_all();
        true
    }

    pub fn reajustar(&self, delta: i64) -> bool {
        let mut v = self.ventanas.lock().unwrap();
        if v.flujos.values().any(|w| w + delta > TOPE) {
            return false;
        }
        v.inicial += delta;
        for w in v.flujos.values_mut() {
            *w += delta;
        }
        self.hay.notify_all();
        true
    }

    pub fn reservar(&self, flujo: u32, quiero: usize, hasta: Instant) -> Option<usize> {
        let mut v = self.ventanas.lock().ok()?;
        loop {
            if !v.abierto {
                return None;
            }
            let suyo = *v.flujos.get(&flujo)?;
            let hueco = suyo.min(v.conexion).min(quiero as i64);
            if hueco > 0 {
                v.conexion -= hueco;
                v.flujos.insert(flujo, suyo - hueco);
                return Some(hueco as usize);
            }
            let queda = hasta.checked_duration_since(Instant::now())?;
            v = self.hay.wait_timeout(v, queda).ok()?.0;
        }
    }

    pub fn cerrar(&self) {
        self.ventanas.lock().unwrap().abierto = false;
        self.hay.notify_all();
    }

    pub fn ventana_de(&self, flujo: u32) -> Option<i64> {
        let v = self.ventanas.lock().unwrap();
        if flujo == 0 { Some(v.conexion) } else { v.flujos.get(&flujo).copied() }
    }
}
