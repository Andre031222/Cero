use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Arc;

use cero_http::Registro;

trait Saluda: Send + Sync {
    fn saludo(&self) -> String;
}

struct Idioma(&'static str);

struct Saludador {
    idioma: Arc<Idioma>,
}

impl Saluda for Saludador {
    fn saludo(&self) -> String {
        format!("hola en {}", self.idioma.0)
    }
}

struct Portada {
    saludador: Arc<Saludador>,
}

fn registro(cuenta: &Arc<AtomicU32>) -> Registro {
    let cuenta = Arc::clone(cuenta);
    let mut r = Registro::nuevo();
    r.poner(Idioma("castellano"));
    r.registrar(move |r| {
        cuenta.fetch_add(1, Ordering::SeqCst);
        Ok(Arc::new(Saludador { idioma: r.obtener::<Idioma>()? }))
    });
    r.registrar(|r| Ok(r.obtener::<Saludador>()? as Arc<dyn Saluda>));
    r.registrar(|r| Ok(Arc::new(Portada { saludador: r.obtener::<Saludador>()? })));
    r
}

fn sin_contar() -> Registro {
    registro(&Arc::new(AtomicU32::new(0)))
}

#[test]
fn rut_032_por_el_tipo_y_por_el_contrato() {
    let r = sin_contar();
    assert_eq!(r.obtener::<Saludador>().unwrap().saludo(), "hola en castellano", "RUT-032");
    assert_eq!(r.obtener::<dyn Saluda>().unwrap().saludo(), "hola en castellano", "RUT-032");
}

#[test]
fn rut_033_la_instancia_es_unica_por_contenedor() {
    let r = sin_contar();
    assert!(Arc::ptr_eq(&r.obtener::<Saludador>().unwrap(), &r.obtener::<Saludador>().unwrap()),
            "RUT-033");
    let otro = sin_contar();
    assert!(!Arc::ptr_eq(&r.obtener::<Saludador>().unwrap(), &otro.obtener::<Saludador>().unwrap()),
            "y única **por contenedor**: otro contenedor es otro mundo");
}

#[test]
fn rut_034_la_cadena_comparte_sus_eslabones() {
    let cuenta = Arc::new(AtomicU32::new(0));
    let r = registro(&cuenta);
    let portada = r.obtener::<Portada>().unwrap();
    let suelto = r.obtener::<Saludador>().unwrap();
    let por_contrato = r.obtener::<dyn Saluda>().unwrap();

    assert!(Arc::ptr_eq(&portada.saludador, &suelto), "RUT-034");
    assert_eq!(por_contrato.saludo(), suelto.saludo());
    assert_eq!(cuenta.load(Ordering::SeqCst), 1, "RUT-034: una sola construcción");
}

#[test]
fn rut_035_un_ciclo_se_cuenta_en_vez_de_colgarse() {
    #[derive(Debug)]
    struct Gallina;
    struct Huevo;

    let mut r = Registro::nuevo();
    r.registrar(|r| {
        r.obtener::<Huevo>()?;
        Ok(Arc::new(Gallina))
    });
    r.registrar(|r| {
        r.obtener::<Gallina>()?;
        Ok(Arc::new(Huevo))
    });

    let fallo = r.obtener::<Gallina>().unwrap_err();
    assert_eq!(fallo.estado, 500);
    assert!(fallo.mensaje.contains("ciclo de dependencias"), "RUT-035: {}", fallo.mensaje);
    assert_eq!(fallo.mensaje.matches("Gallina").count(), 2, "la cadena, no solo dónde se cerró");
    assert!(fallo.mensaje.contains("Huevo"));
}

#[test]
fn rut_036_lo_no_registrado_no_se_inventa() {
    #[derive(Debug)]
    struct Nadie;
    let r = sin_contar();
    assert!(!r.tiene::<Nadie>());
    let fallo = r.obtener::<Nadie>().unwrap_err();
    assert_eq!(fallo.estado, 500);
    assert!(fallo.mensaje.contains("Nadie"), "RUT-036: y dice cuál falta");
}

#[test]
fn despues_de_un_ciclo_el_contenedor_sigue_sirviendo() {
    #[derive(Debug)]
    struct Gallina;
    let mut r = Registro::nuevo();
    r.registrar(|r| {
        r.obtener::<Gallina>()?;
        Ok(Arc::new(Gallina))
    });
    r.poner(Idioma("castellano"));

    assert!(r.obtener::<Gallina>().is_err());
    assert!(r.obtener::<Gallina>().is_err(), "y el segundo intento dice lo mismo, no otra cosa");
    assert_eq!(r.obtener::<Idioma>().unwrap().0, "castellano");
}
