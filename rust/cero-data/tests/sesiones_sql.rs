//! El almacén de sesiones en una tabla: `SES-012` y `SES-013`.
//!
//! Corre sin base de datos, con la implementación en memoria del contrato. Eso no es un atajo: lo
//! que se comprueba aquí es el SQL que se emite y lo que se hace con las filas, y eso es lo mismo
//! contra un motor de verdad. Java usa H2 para esto, que es una dependencia de prueba.

use std::sync::Arc;
use std::time::Duration;

use cero_data::memoria::EnMemoria;
use cero_data::sesiones::AlmacenSql;
use cero_http::sesion::Sesiones;

fn almacen(tabla: &str) -> (AlmacenSql, EnMemoria) {
    let fuente = EnMemoria::nueva();
    let a = AlmacenSql::nuevo(
        Arc::new(fuente.clone()),
        tabla,
        Duration::from_secs(600),
        Some(Duration::from_secs(8 * 3600)),
    )
    .expect("almacén");
    (a, fuente)
}

/// `SES-013`: el nombre de la tabla se da al construir y no hay valor por omisión. Exigir una
/// tabla llamada `sesiones` obliga a quien ya tiene un esquema a renombrar lo suyo para encajar en
/// el framework, que es la relación al revés.
#[test]
fn ses_013_el_nombre_de_la_tabla_lo_pone_la_aplicacion() {
    for tabla in ["sesiones", "web_sesion", "AppSessions"] {
        let (a, fuente) = almacen(tabla);
        assert_eq!(a.tabla(), tabla, "SES-013");
        a.crear().expect("crea");
        assert_eq!(fuente.cuantas(tabla), 1, "SES-013: y escribe en esa, no en otra");
    }
}

/// Y un nombre que no es un identificador se rechaza **al construir**. No se puede parametrizar un
/// identificador en SQL, así que si viene de fuera y no se comprueba, es inyección.
#[test]
fn ses_013_un_nombre_de_tabla_que_no_lo_es_no_se_admite() {
    for malo in ["", "sesiones; DROP TABLE usuarios", "se siones", "s*"] {
        let fuente = Arc::new(EnMemoria::nueva());
        assert!(AlmacenSql::nuevo(fuente, malo, Duration::from_secs(60), None).is_err(),
                "SES-013: {malo:?} no es un identificador");
    }
}

/// `SES-012`: lo que una instancia abrió, otra lo reconoce. Lo que separa poder poner una segunda
/// instancia detrás del balanceador de no poder — y aquí es de verdad entre procesos, porque lo
/// único que comparten es la tabla.
#[test]
fn ses_012_dos_almacenes_sobre_la_misma_tabla_ven_lo_mismo() {
    let fuente = EnMemoria::nueva();
    let reglas = || Duration::from_secs(600);
    let una = AlmacenSql::nuevo(Arc::new(fuente.clone()), "sesiones", reglas(), None).unwrap();
    let otra = AlmacenSql::nuevo(Arc::new(fuente.clone()), "sesiones", reglas(), None).unwrap();

    let sesion = una.crear().expect("crea");
    let id = sesion.lock().unwrap().id().to_string();
    sesion.lock().unwrap().poner("usuario", "ana").unwrap();
    una.guardar(&sesion);

    let vista = otra.recuperar(Some(&id)).expect("SES-012");
    assert_eq!(vista.lock().unwrap().leer("usuario").unwrap().map(String::as_str), Some("ana"),
               "SES-012");
}

/// `SES-001` sigue valiendo con la tabla por medio: sin cookie no se busca, y lo que no está no se
/// crea. Un almacén que crea al leer convierte cada rastreador en filas huérfanas, y en una tabla
/// eso además no se recoge solo.
#[test]
fn ses_001_sobre_la_tabla_leer_no_crea() {
    let (a, fuente) = almacen("sesiones");
    assert!(a.recuperar(None).is_none(), "SES-001");
    assert!(a.recuperar(Some("inventado")).is_none(), "SES-001");
    assert_eq!(fuente.cuantas("sesiones"), 0, "SES-001: y no dejó nada escrito");
}

/// Una sesión caducada no vuelve a autenticar a nadie, y se borra al tocarla en vez de esperar un
/// barrido: así no hace falta un hilo que limpie, y la fila muerta no sirve por mucho que quede.
#[test]
fn una_sesion_caducada_se_borra_al_tocarla() {
    let fuente = EnMemoria::nueva();
    let a = AlmacenSql::nuevo(Arc::new(fuente.clone()), "sesiones", Duration::ZERO, None).unwrap();
    let id = a.crear().unwrap().lock().unwrap().id().to_string();
    assert_eq!(fuente.cuantas("sesiones"), 1);

    std::thread::sleep(Duration::from_millis(5));
    assert!(a.recuperar(Some(&id)).is_none(), "caducada por inactividad");
    assert_eq!(fuente.cuantas("sesiones"), 0, "y la fila ya no está");
}

/// `SES-005` y `SES-006`: rotar cambia el identificador y conserva los atributos, y una sesión
/// invalidada no se rota. En una tabla son dos pasos, y el orden importa: primero se escribe la
/// fila nueva y después se borra la vieja, porque al revés un fallo entre los dos pasos pierde la
/// sesión mientras que así lo peor que deja es una fila de sobra que caduca sola.
#[test]
fn ses_005_y_006_rotar_sobre_la_tabla() {
    let (a, fuente) = almacen("sesiones");
    let sesion = a.crear().unwrap();
    sesion.lock().unwrap().poner("usuario", "ana").unwrap();
    a.guardar(&sesion);
    let viejo = sesion.lock().unwrap().id().to_string();

    let nuevo = a.rotar(&sesion).expect("SES-005");
    assert_ne!(nuevo, viejo, "SES-005");
    assert_eq!(fuente.cuantas("sesiones"), 1, "la vieja se borró, no se acumula");
    let vista = a.recuperar(Some(&nuevo)).expect("SES-005: se encuentra por el nuevo");
    assert_eq!(vista.lock().unwrap().leer("usuario").unwrap().map(String::as_str), Some("ana"),
               "SES-005: con sus atributos");
    assert!(a.recuperar(Some(&viejo)).is_none(), "SES-005: y el viejo ya no vale");

    sesion.lock().unwrap().invalidar();
    assert!(a.rotar(&sesion).is_err(), "SES-006");
}

/// Invalidar borra la fila. Dejarla sería dejar viva en la base una sesión que el proceso ya
/// considera muerta, y la siguiente instancia que la leyera no sabría que lo está.
#[test]
fn invalidar_borra_la_fila() {
    let (a, fuente) = almacen("sesiones");
    let sesion = a.crear().unwrap();
    sesion.lock().unwrap().invalidar();
    a.guardar(&sesion);
    assert_eq!(fuente.cuantas("sesiones"), 0);
}

/// Guardar sin cambios no escribe. Hacerlo serían dos viajes a la base por cada `GET` que no tocó
/// nada, y eso convierte el almacén compartido en el cuello de botella del servidor.
#[test]
fn guardar_sin_cambios_no_escribe() {
    let (a, fuente) = almacen("sesiones");
    let sesion = a.crear().unwrap();
    let id = sesion.lock().unwrap().id().to_string();

    let recuperada = a.recuperar(Some(&id)).expect("está");
    a.guardar(&recuperada);
    a.guardar(&recuperada);
    assert_eq!(fuente.cuantas("sesiones"), 1, "ni duplica ni reescribe");
}

/// El DDL se publica para que la migración no haya que adivinarla, y nombra la tabla que se le
/// pida: si el esquema dijera otra cosa que el almacén, `SES-013` sería mentira a medias.
#[test]
fn ses_013_el_esquema_nombra_la_tabla_pedida() {
    let ddl = AlmacenSql::esquema("web_sesion");
    assert!(ddl.contains("web_sesion"), "SES-013");
    for columna in ["id", "datos", "creada", "tocada"] {
        assert!(ddl.contains(columna), "falta la columna {columna}");
    }
}
