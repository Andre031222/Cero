use std::any::{type_name, Any, TypeId};
use std::cell::RefCell;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use crate::fallo::Fallo;

type Caja = Box<dyn Any + Send + Sync>;
type Fabrica = Box<dyn Fn(&Registro) -> Result<Caja, Fallo> + Send + Sync>;

struct Receta {
    nombre: &'static str,
    fabrica: Fabrica,
}

thread_local! {
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

    pub fn poner<T: Send + Sync + 'static>(&mut self, valor: T) -> &mut Registro {
        let compartido = Arc::new(valor);
        self.registrar(move |_| Ok(Arc::clone(&compartido)))
    }

    pub fn tiene<T: ?Sized + 'static>(&self) -> bool {
        self.recetas.contains_key(&TypeId::of::<T>())
    }

    pub fn obtener<T: ?Sized + Send + Sync + 'static>(&self) -> Result<Arc<T>, Fallo> {
        let clave = TypeId::of::<T>();
        if let Some(ya) = self.guardado(clave) {
            return Ok(ya);
        }
        let Some(receta) = self.recetas.get(&clave) else {
            return Err(Fallo::interno(&format!("no hay nada registrado para {}", type_name::<T>())));
        };
        let _guardia = Guardia::nueva(clave, receta.nombre)?;

        let recien = (receta.fabrica)(self)?;
        let mut hechos = self.hechos.lock().map_err(|_| Fallo::interno("el registro se envenenó"))?;
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
