//! El crédito de salida: cuánto se le puede mandar al cliente ahora mismo.
//!
//! Vive aparte de `Sesion` porque tiene otro dueño. Las ventanas de entrada las lleva el hilo que
//! lee, de una en una; estas las gastan varios hilos de flujo a la vez y las repone el que lee, así
//! que hacen falta un candado y una variable de condición, no un `&mut`.
//!
//! Sin esto un servidor manda todo lo que tiene en cuanto lo tiene. Va bien contra un cliente que
//! lee rápido y desborda al que no: el control de flujo existe porque el que recibe también tiene
//! memoria finita, y saltárselo es la mitad de lo que h2spec marcaba.

use std::collections::HashMap;
use std::sync::{Condvar, Mutex};

/// §6.9.1: ninguna ventana puede pasar de aquí, y el que la pasa es quien manda el incremento.
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
    /// `inicial` es el `SETTINGS_INITIAL_WINDOW_SIZE` del cliente, que vale para cada flujo. La de
    /// la conexión entera empieza siempre en 65 535 y solo la mueve un WINDOW_UPDATE (§6.9.2).
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

    /// Un flujo anulado deja de tener crédito y nunca lo vuelve a tener: su hilo despierta y se va
    /// en vez de quedarse esperando por una respuesta que ya no quiere nadie.
    pub fn anular(&self, flujo: u32) {
        self.ventanas.lock().unwrap().flujos.remove(&flujo);
        self.hay.notify_all();
    }

    /// Un WINDOW_UPDATE. `false` si el incremento pasa del tope; quien llama sabe si eso corta el
    /// flujo o la conexión.
    pub fn ampliar(&self, flujo: u32, cuanto: i64) -> bool {
        let mut v = self.ventanas.lock().unwrap();
        let ventana = if flujo == 0 { &mut v.conexion } else {
            // Un WINDOW_UPDATE de un flujo que ya se fue se ignora: pudo cruzarse con el RST.
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

    /// §6.9.2: una ventana inicial nueva mueve la de los flujos ya abiertos, no solo la de los
    /// siguientes. Si no, el cliente y el servidor cuentan distinto y uno de los dos corta.
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

    /// Espera hasta que haya algo que mandar y lo aparta. Devuelve cuánto —nunca más de `quiero`,
    /// puede ser menos— o `None` si el flujo se anuló o la conexión se acabó.
    pub fn reservar(&self, flujo: u32, quiero: usize) -> Option<usize> {
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
            v = self.hay.wait(v).ok()?;
        }
    }

    /// Se acabó el socket. Despierta a todo el que esperaba: si no, el hilo que lee se queda
    /// esperándolos a ellos y ellos a un crédito que ya no va a llegar.
    pub fn cerrar(&self) {
        self.ventanas.lock().unwrap().abierto = false;
        self.hay.notify_all();
    }

    pub fn ventana_de(&self, flujo: u32) -> Option<i64> {
        let v = self.ventanas.lock().unwrap();
        if flujo == 0 { Some(v.conexion) } else { v.flujos.get(&flujo).copied() }
    }
}
