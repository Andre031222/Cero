//! El contenedor de dependencias: `spec/ruteo.md`, `RUT-032` a `RUT-036`.
//!
//! En Java esto se resuelve por reflexión: se mira el constructor, se piden sus parámetros y se
//! construye. Rust no tiene reflexión, así que aquí cada servicio se registra con la función que
//! lo construye. El contrato no pide reflexión —pide resolver por tipo y por contrato, unicidad,
//! cadenas y detección de ciclos—, y eso es lo que se cumple. Es la segunda vez que `spec/`
//! demuestra no llevar dentro una decisión de Java, después del ruteo.
//!
//! Registrar es la única parte que cambia, y a cambio se gana algo: un tipo sin registrar no
//! compila donde se usa, en vez de fallar al arrancar.

use std::any::{type_name, Any, TypeId};
use std::cell::RefCell;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use crate::fallo::Fallo;

/// Lo que se guarda es el `Arc`, no lo que hay dentro. Eso es lo que permite que un contrato
/// —`dyn Algo`, que no tiene tamaño— entre en la misma tabla que un tipo concreto.
type Caja = Box<dyn Any + Send + Sync>;
type Fabrica = Box<dyn Fn(&Registro) -> Result<Caja, Fallo> + Send + Sync>;

struct Receta {
    nombre: &'static str,
    fabrica: Fabrica,
}

thread_local! {
    /// `RUT-035`. La pila va por hilo porque una cadena se resuelve entera en el hilo que la pidió:
    /// dos hilos construyendo cosas distintas no forman un ciclo entre ellos.
    static EN_CURSO: RefCell<Vec<(TypeId, &'static str)>> = const { RefCell::new(Vec::new()) };
}

#[derive(Default)]
pub struct Registro {
    recetas: HashMap<TypeId, Receta>,
    hechos: Mutex<HashMap<TypeId, Caja>>,
}

impl Registro {
    pub fn nuevo() -> Registro {
        Registro::default()
    }

    /// Registra cómo se construye algo. `T` puede ser un tipo concreto o un contrato: para lo
    /// segundo la función devuelve el `Arc` ya convertido, que es donde Rust sabe cuál es el tipo
    /// de verdad. `RUT-032`, las dos mitades.
    ///
    /// ```ignore
    /// registro.registrar(|_| Ok(Arc::new(Reloj::nuevo())));
    /// registro.registrar(|r| Ok(r.obtener::<Reloj>()? as Arc<dyn Hora>));
    /// ```
    pub fn registrar<T, F>(&mut self, fabrica: F) -> &mut Registro
    where
        T: ?Sized + Send + Sync + 'static,
        F: Fn(&Registro) -> Result<Arc<T>, Fallo> + Send + Sync + 'static,
    {
        let receta = Receta {
            nombre: type_name::<T>(),
            fabrica: Box::new(move |r| fabrica(r).map(|a| Box::new(a) as Caja)),
        };
        self.recetas.insert(TypeId::of::<T>(), receta);
        self
    }

    /// Algo que ya está construido. Atajo de `registrar` para lo que no depende de nada.
    pub fn poner<T: Send + Sync + 'static>(&mut self, valor: T) -> &mut Registro {
        let compartido = Arc::new(valor);
        self.registrar(move |_| Ok(Arc::clone(&compartido)))
    }

    pub fn tiene<T: ?Sized + 'static>(&self) -> bool {
        self.recetas.contains_key(&TypeId::of::<T>())
    }

    /// `RUT-033` y `RUT-034`: lo construido se guarda, así que dos resoluciones devuelven lo
    /// mismo y una cadena de cualquier profundidad comparte sus eslabones.
    pub fn obtener<T: ?Sized + Send + Sync + 'static>(&self) -> Result<Arc<T>, Fallo> {
        let clave = TypeId::of::<T>();
        if let Some(ya) = self.guardado(clave) {
            return Ok(ya);
        }
        // `RUT-036`: lo que no está registrado falla aquí y no se construye a medias.
        let Some(receta) = self.recetas.get(&clave) else {
            return Err(Fallo::interno(&format!("no hay nada registrado para {}", type_name::<T>())));
        };
        let _guardia = Guardia::nueva(clave, receta.nombre)?;

        // La fábrica corre **fuera** del candado, a propósito: vuelve a entrar en `obtener` por
        // cada dependencia suya, y construir dentro sería un candado no reentrante bloqueándose
        // contra sí mismo en la segunda cadena de dos eslabones. Java tropezó con la misma piedra
        // y con otra forma, `computeIfAbsent` recursivo sobre el mismo mapa.
        let recien = (receta.fabrica)(self)?;
        let mut hechos = self.hechos.lock().map_err(|_| Fallo::interno("el registro se envenenó"))?;
        // Si dos hilos llegaron a la vez, gana el primero que guardó: `RUT-033` dice que la
        // instancia es única, no que se construya una sola vez.
        let caja = hechos.entry(clave).or_insert(recien);
        descajar(caja).ok_or_else(|| Fallo::interno("el registro guardó otro tipo"))
    }

    fn guardado<T: ?Sized + Send + Sync + 'static>(&self, clave: TypeId) -> Option<Arc<T>> {
        descajar(self.hechos.lock().ok()?.get(&clave)?)
    }
}

fn descajar<T: ?Sized + Send + Sync + 'static>(caja: &Caja) -> Option<Arc<T>> {
    caja.downcast_ref::<Arc<T>>().map(Arc::clone)
}

/// Mientras vive, su tipo está en la pila de construcción. `RUT-035`: entrar dos veces al mismo
/// tipo es un ciclo, y se cuenta con la cadena entera porque el nombre del tipo repetido solo dice
/// dónde se cerró, no por dónde se pasó.
struct Guardia;

impl Guardia {
    fn nueva(clave: TypeId, nombre: &'static str) -> Result<Guardia, Fallo> {
        EN_CURSO.with(|pila| {
            let mut pila = pila.borrow_mut();
            if pila.iter().any(|(t, _)| *t == clave) {
                let mut camino: Vec<&str> = pila.iter().map(|(_, n)| *n).collect();
                camino.push(nombre);
                return Err(Fallo::interno(&format!("ciclo de dependencias: {}", camino.join(" → "))));
            }
            pila.push((clave, nombre));
            Ok(Guardia)
        })
    }
}

impl Drop for Guardia {
    fn drop(&mut self) {
        EN_CURSO.with(|pila| {
            pila.borrow_mut().pop();
        });
    }
}
