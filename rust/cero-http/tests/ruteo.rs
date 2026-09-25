//! Una prueba por requisito de `spec/ruteo.md`, citándolo.
//!
//! El servidor se maneja sin socket: `responder` es el pipeline entero, se le da una petición y
//! devuelve la respuesta. Un puerto por medio convertiría cada prueba en una carrera y no
//! comprobaría nada más, porque lo que hay entre el socket y esto ya lo juzgan los 23 vectores del
//! banco de conformidad.

use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};

use cero_http::ruta::{Patron, Resolucion};
use cero_http::{Contexto, Fallo, Json, Peticion, Respuesta, Router, Servidor};

fn peticion(metodo: &str, destino: &str) -> Peticion {
    Peticion {
        metodo: metodo.into(),
        destino: destino.into(),
        version: "HTTP/1.1".into(),
        cabeceras: [("host".to_string(), "x".to_string())].into_iter().collect(),
        cuerpo: Vec::new(),
    }
}

fn cuerpo(r: &Respuesta) -> String {
    String::from_utf8_lossy(&r.cuerpo).into_owned()
}

fn cabecera<'a>(r: &'a Respuesta, n: &str) -> Option<&'a str> {
    r.extra.iter().find(|(k, _)| k == n).map(|(_, v)| v.as_str())
}

/// Qué acción atiende un GET, para no repetir el `match` en cada prueba de prioridad.
fn atiende(r: &Router, camino: &str) -> String {
    match r.resolver("GET", camino) {
        Resolucion::Encontrada(nombre, _) => nombre,
        otra => panic!("no resolvió {camino}: {otra:?}"),
    }
}

// ── Patrones ────────────────────────────────────────────────────────────────────────────────

/// `RUT-001`: ni un segmento de más ni uno de menos. Un patrón que casara prefijos haría que
/// `/admin` atendiera `/admin/borrar-todo`.
#[test]
fn rut_001_el_numero_de_segmentos_es_exacto() {
    let p = Patron::nuevo("/a/{id}").unwrap();
    assert!(p.casa("/a/7").is_some(), "RUT-001");
    assert!(p.casa("/a").is_none(), "RUT-001: uno menos no casa");
    assert!(p.casa("/a/7/b").is_none(), "RUT-001: uno más tampoco");
}

/// `RUT-002` y `RUT-003`: cada variable se recupera por su nombre, y en un mismo camino caben
/// varias. Con una sola no se distingue capturar de acertar por posición.
#[test]
fn rut_002_y_003_las_variables_se_recuperan_por_nombre() {
    let c = Patron::nuevo("/{seccion}/{id}/{hoja}").unwrap().casa("/foros/7/respuestas").unwrap();
    assert_eq!(c.get("seccion").map(String::as_str), Some("foros"), "RUT-002");
    assert_eq!(c.get("id").map(String::as_str), Some("7"), "RUT-003");
    assert_eq!(c.get("hoja").map(String::as_str), Some("respuestas"), "RUT-003");
}

/// `RUT-004`: la barra final no cambia la ruta. Si la cambiara, la misma página tendría dos URL y
/// las métricas contarían dos series por lo mismo.
#[test]
fn rut_004_la_barra_final_se_normaliza() {
    let p = Patron::nuevo("/a/{id}").unwrap();
    assert_eq!(p.casa("/a/7"), p.casa("/a/7/"), "RUT-004");
}

/// `RUT-005`: el comodín se lleva el resto, sea un segmento o cinco.
#[test]
fn rut_005_el_comodin_captura_el_resto() {
    let p = Patron::nuevo("/estaticos/*").unwrap();
    assert_eq!(p.casa("/estaticos/a.css").unwrap().get("*").map(String::as_str), Some("a.css"));
    assert_eq!(p.casa("/estaticos/css/tema/a.css").unwrap().get("*").map(String::as_str),
               Some("css/tema/a.css"), "RUT-005");
}

/// `RUT-006` y `RUT-007`: un patrón mal escrito se rechaza **al construirlo**. Detectado al
/// arrancar es un error del programador; detectado al resolver es un 500 en producción.
#[test]
fn rut_006_y_007_un_patron_malo_no_llega_a_existir() {
    assert!(Patron::nuevo("/a/*/b").is_err(), "RUT-006");
    assert!(Patron::nuevo("/a/{id").is_err(), "RUT-007");
    assert!(Patron::nuevo("/a/id}").is_err(), "RUT-007: y sin abrir");
    assert!(Patron::nuevo("/a/{}").is_err(), "RUT-007: y sin nombre");
}

/// `RUT-008`: entre dos que casan gana el literal. Si ganara el variable, `/usuarios/nuevo` lo
/// atendería `/usuarios/{id}` y el formulario de alta acabaría buscando al usuario «nuevo».
#[test]
fn rut_008_el_literal_gana_al_variable() {
    let r = Router::nuevo()
        .ruta("GET", "/usuarios/{id}", "ver").unwrap()
        .ruta("GET", "/usuarios/nuevo", "alta").unwrap();
    assert_eq!(atiende(&r, "/usuarios/nuevo"), "alta", "RUT-008");
    assert_eq!(atiende(&r, "/usuarios/7"), "ver", "y la variable sigue atendiendo lo suyo");
}

/// `RUT-009`, `RUT-010` y `RUT-011`: un camino sin ruta no es lo mismo que un verbo no admitido, y
/// el segundo tiene que poder enumerar los verbos que sí valen. `HEAD` se resuelve contra el `GET`
/// del mismo camino, así que aparece en esa lista aunque nadie lo declarara.
#[test]
fn rut_009_a_011_no_hay_ruta_no_es_verbo_equivocado() {
    let r = Router::nuevo().ruta("GET", "/a", "ver").unwrap();
    assert_eq!(r.resolver("GET", "/no-existe"), Resolucion::NoHay, "RUT-009");
    assert_eq!(r.resolver("POST", "/a"),
               Resolucion::VerboNoPermitido(vec!["GET".into(), "HEAD".into()]), "RUT-010");
    let Resolucion::Encontrada(nombre, _) = r.resolver("HEAD", "/a") else {
        panic!("RUT-011: HEAD se resuelve contra el GET")
    };
    assert_eq!(nombre, "ver", "RUT-011");
}

// ── El pipeline ─────────────────────────────────────────────────────────────────────────────

fn servidor() -> Servidor {
    let router = Router::nuevo()
        .ruta("GET", "/a", "texto").unwrap()
        .ruta("POST", "/a", "texto").unwrap()
        .ruta("GET", "/objeto", "objeto").unwrap()
        .ruta("GET", "/nada", "nada").unwrap()
        .ruta("GET", "/fuera", "fuera").unwrap()
        .ruta("GET", "/binario", "binario").unwrap()
        .ruta("GET", "/descarga", "descarga").unwrap()
        .ruta("GET", "/estalla", "estalla").unwrap()
        .ruta("GET", "/no-esta", "no_esta").unwrap()
        .ruta("GET", "/numero/{id}", "numero").unwrap()
        .ruta("GET", "/busca", "busca").unwrap()
        .ruta("POST", "/json", "json").unwrap()
        .ruta("GET", "/agente", "agente").unwrap()
        .ruta("GET", "/eco-cuerpo", "eco_cuerpo").unwrap();

    Servidor::nuevo(router)
        .accion("texto", |_| Respuesta::texto("hola"))
        .accion("objeto", |_| Respuesta::json(Json::objeto(vec![("a", Json::Numero(1.0))])))
        .accion("nada", |_| Respuesta::nada())
        .accion("fuera", |_| Respuesta::redirigir("/a"))
        .accion("binario", |_| Respuesta {
            cuerpo: (0u16..=255).map(|b| b as u8).collect(),
            ..Respuesta::estado(200, "")
        })
        .accion("descarga", |_| Respuesta::descarga(b"x".to_vec(), "a\"b\r\nSet-Cookie: robada", "application/octet-stream"))
        .accion("estalla", |_| Err(Fallo::interno("la contraseña de la base es 'hunter2'")))
        .accion("no_esta", |_| Err(Fallo::nuevo(404, "el artículo no existe")))
        .accion("numero", |c| Ok(Respuesta::texto(&format!("{}", c.variable_como::<u32>("id")?))))
        .accion("busca", |c| Respuesta::texto(&c.consulta_o("q", "")))
        .accion("json", |c| Ok(Respuesta::texto(
            c.cuerpo_json()?.get("nombre").and_then(Json::texto).unwrap_or("sin nombre"))))
        .accion("agente", |c| Respuesta::texto(c.cabecera("user-agent").unwrap_or("sin agente")))
        .accion("eco_cuerpo", |c| Respuesta { cuerpo: c.peticion.cuerpo.clone(), ..Respuesta::estado(200, "") })
}

/// `RUT-012` y `RUT-013`: 404 y 405 son respuestas distintas, y el 405 lleva `Allow`. Sin `Allow`,
/// quien llama mal sabe que se equivocó pero no en qué.
#[test]
fn rut_012_y_013_el_404_y_el_405() {
    let s = servidor();
    assert_eq!(s.responder(&peticion("GET", "/no-hay-nada"), "x").estado, 404, "RUT-012");
    let r = s.responder(&peticion("DELETE", "/a"), "x");
    assert_eq!(r.estado, 405, "RUT-013");
    assert_eq!(cabecera(&r, "Allow"), Some("GET, HEAD, POST"), "RUT-013");
}

/// `RUT-014` y `RUT-015`: la variable se convierte al tipo que la acción pide, y la que no
/// convierte es 400 y no 500. El cliente mandó mal la petición; el servidor no falló.
#[test]
fn rut_014_y_015_la_variable_se_convierte_o_es_400() {
    let s = servidor();
    assert_eq!(cuerpo(&s.responder(&peticion("GET", "/numero/42"), "x")), "42", "RUT-014");
    assert_eq!(s.responder(&peticion("GET", "/numero/ocho"), "x").estado, 400, "RUT-015");
}

/// `RUT-016`: un defecto de cadena vacía no es lo mismo que «sin defecto». Para una búsqueda,
/// ausente puede querer decir «todo» y vacío «nada», y confundirlos cambia el resultado.
#[test]
fn rut_016_el_defecto_vacio_se_distingue_del_ausente() {
    let s = servidor();
    assert_eq!(cuerpo(&s.responder(&peticion("GET", "/busca?q=gatos"), "x")), "gatos");
    assert_eq!(cuerpo(&s.responder(&peticion("GET", "/busca"), "x")), "", "RUT-016");
}

/// `RUT-017`: el cuerpo JSON llega estructurado, y uno mal formado es 400.
#[test]
fn rut_017_el_cuerpo_json_se_vincula() {
    let s = servidor();
    let mut p = peticion("POST", "/json");
    p.cuerpo = br#"{"nombre":"ana"}"#.to_vec();
    assert_eq!(cuerpo(&s.responder(&p, "x")), "ana", "RUT-017");

    let mut roto = peticion("POST", "/json");
    roto.cuerpo = b"{no".to_vec();
    assert_eq!(s.responder(&roto, "x").estado, 400, "y uno mal formado es 400, no 500");
}

/// `RUT-018`: una cabecera ausente queda sin valor, no falla. Tratar la falta de `User-Agent` como
/// una petición mal formada convierte en 400 lo que era un 200.
#[test]
fn rut_018_una_cabecera_ausente_no_es_un_fallo() {
    let s = servidor();
    assert_eq!(cuerpo(&s.responder(&peticion("GET", "/agente"), "x")), "sin agente", "RUT-018");
    let mut con = peticion("GET", "/agente");
    con.cabeceras.insert("user-agent".into(), "curl".into());
    assert_eq!(cuerpo(&s.responder(&con, "x")), "curl");
}

/// `RUT-019`, `RUT-020` y `RUT-021`: texto, JSON, 204 y la redirección.
#[test]
fn rut_019_a_021_las_formas_de_responder() {
    let s = servidor();
    let texto = s.responder(&peticion("GET", "/a"), "x");
    assert!(texto.tipo.starts_with("text/plain"), "RUT-019");
    assert!(s.responder(&peticion("GET", "/objeto"), "x").tipo.starts_with("application/json"),
            "RUT-019");
    assert_eq!(s.responder(&peticion("GET", "/nada"), "x").estado, 204, "RUT-020");
    let fuera = s.responder(&peticion("GET", "/fuera"), "x");
    assert_eq!(fuera.estado, 302, "RUT-021");
    assert_eq!(cabecera(&fuera, "Location"), Some("/a"), "RUT-021");
}

/// `RUT-022`: el nombre de archivo se sanea **antes** de ponerlo en la cabecera. Por aquí se
/// intentó colar una cookie: un salto de línea cierra la cabecera y lo que sigue es otra.
#[test]
fn rut_022_el_nombre_de_la_descarga_se_sanea() {
    let r = servidor().responder(&peticion("GET", "/descarga"), "x");
    let disposicion = cabecera(&r, "Content-Disposition").expect("RUT-022");
    assert!(!disposicion.contains('\r') && !disposicion.contains('\n'), "RUT-022: {disposicion}");
    assert!(cabecera(&r, "Set-Cookie").is_none(), "RUT-022: y no se coló ninguna cookie");
}

/// `RUT-023`: el binario llega octeto a octeto. Se prueban los 256 valores porque el modo de
/// fallar es sutil: si el cuerpo pasa por una cadena, lo que está por encima de 0x7F se convierte
/// en el carácter de sustitución y el archivo llega con un tamaño parecido y corrupto. Un «hola»
/// en ASCII pasaría esta prueba sin enterarse de nada.
#[test]
fn rut_023_el_binario_llega_entero() {
    let esperado: Vec<u8> = (0u16..=255).map(|b| b as u8).collect();
    let s = servidor();
    assert_eq!(s.responder(&peticion("GET", "/binario"), "x").cuerpo, esperado, "RUT-023");

    let mut sube = peticion("GET", "/eco-cuerpo");
    sube.cuerpo = esperado.clone();
    assert_eq!(s.responder(&sube, "x").cuerpo, esperado, "RUT-023: y de subida también");
}

/// `RUT-024` y `RUT-025`: el 500 no cuenta lo que pasó dentro, pero sí dice qué ruta falló. Lo
/// primero es que el mensaje interno habla de las tripas del servidor; lo segundo, que sin saber
/// qué ruta fue, quien lo recibe no puede informarlo.
#[test]
fn rut_024_y_025_el_500_no_cuenta_de_mas_pero_dice_donde() {
    let r = servidor().responder(&peticion("GET", "/estalla"), "x");
    assert_eq!(r.estado, 500);
    assert!(!cuerpo(&r).contains("hunter2"), "RUT-024: {}", cuerpo(&r));
    assert!(cuerpo(&r).contains("/estalla"), "RUT-025: {}", cuerpo(&r));
}

/// `RUT-026`: un estado declarado se conserva con su mensaje. «El artículo no existe» es un 404
/// que el cliente tiene que poder leer, y tratarlo como interno lo convertiría en un 500 mudo.
#[test]
fn rut_026_un_estado_declarado_se_conserva() {
    let r = servidor().responder(&peticion("GET", "/no-esta"), "x");
    assert_eq!(r.estado, 404, "RUT-026");
    assert!(cuerpo(&r).contains("el artículo no existe"), "RUT-026");
}

/// `RUT-027`: el manejador declarado fija el estado y construye el cuerpo, y manda incluso sobre
/// un 500: solo la aplicación sabe si su error interno es contable.
#[test]
fn rut_027_el_manejador_de_error_decide() {
    let s = servidor().al_fallar(|_, f| {
        let cuerpo = Json::objeto(vec![("motivo", Json::Texto(f.mensaje.clone()))]);
        Respuesta { estado: 422, ..Respuesta::json(cuerpo) }
    });
    let r = s.responder(&peticion("GET", "/no-esta"), "x");
    assert_eq!(r.estado, 422, "RUT-027");
    assert_eq!(cuerpo(&r), r#"{"motivo":"el artículo no existe"}"#, "RUT-027");
}

/// `RUT-028`: el middleware envuelve la acción en el orden en que se declaró, y el primero
/// declarado es el más exterior. El orden se comprueba con una traza de entrada y salida, no
/// contando llamadas: dos middlewares que corren en cualquier orden pasarían eso último.
#[test]
fn rut_028_el_middleware_envuelve_en_orden() {
    let traza = Arc::new(Mutex::new(Vec::new()));
    let (uno, dos) = (Arc::clone(&traza), Arc::clone(&traza));
    let s = servidor()
        .usar(move |c, siguiente| {
            uno.lock().unwrap().push("entra 1");
            let r = siguiente(c);
            uno.lock().unwrap().push("sale 1");
            r
        })
        .usar(move |c, siguiente| {
            dos.lock().unwrap().push("entra 2");
            let r = siguiente(c);
            dos.lock().unwrap().push("sale 2");
            r
        });
    s.responder(&peticion("GET", "/a"), "x");
    assert_eq!(*traza.lock().unwrap(), ["entra 1", "entra 2", "sale 2", "sale 1"], "RUT-028");
}

/// `RUT-029`: el middleware corre también cuando no hay ruta. Es la que más se incumple en la
/// práctica, y el precio es que las respuestas más fáciles de provocar desde fuera —los 404— sean
/// las únicas que salen sin lo que el middleware pone.
#[test]
fn rut_029_el_middleware_ve_tambien_los_404() {
    let vistos = Arc::new(AtomicU32::new(0));
    let contador = Arc::clone(&vistos);
    let s = servidor().usar(move |c, siguiente| {
        contador.fetch_add(1, Ordering::SeqCst);
        siguiente(c).cabecera("X-Paso-Por-Aqui", "si")
    });
    let r = s.responder(&peticion("GET", "/no-hay-nada"), "x");
    assert_eq!(r.estado, 404);
    assert_eq!(vistos.load(Ordering::SeqCst), 1, "RUT-029");
    assert_eq!(cabecera(&r, "X-Paso-Por-Aqui"), Some("si"), "RUT-029");
}

/// `RUT-030` y `RUT-031`: el manejador por defecto atiende lo no enrutado y su respuesta lleva
/// igualmente lo que puso el middleware. Lo que **no** toca es el 405: devolverle la página del
/// SPA a quien llama mal a la API le esconde su propio error detrás de un 200.
#[test]
fn rut_030_y_031_el_manejador_por_defecto_no_se_come_el_405() {
    let s = servidor()
        .usar(|c, siguiente| siguiente(c).cabecera("X-Frame-Options", "DENY"))
        .por_defecto(|_| Respuesta::html("<!doctype html><title>spa</title>"));

    let spa = s.responder(&peticion("GET", "/una/ruta/del/cliente"), "x");
    assert_eq!(spa.estado, 200, "RUT-030");
    assert!(cuerpo(&spa).contains("spa"), "RUT-030");
    assert_eq!(cabecera(&spa, "X-Frame-Options"), Some("DENY"), "RUT-030");

    let mal = s.responder(&peticion("DELETE", "/a"), "x");
    assert_eq!(mal.estado, 405, "RUT-031");
    assert_eq!(cabecera(&mal, "Allow"), Some("GET, HEAD, POST"), "RUT-031");
}

/// `RUT-037`: el contexto está disponible sin declararlo como dependencia, y desde él se llega al
/// contenedor. Son las dos mitades de lo mismo: lo de la petición viene dado y lo de la
/// aplicación se pide.
#[test]
fn rut_037_el_contexto_viene_dado_y_el_contenedor_se_pide() {
    struct Saludo(&'static str);
    let router = Router::nuevo().ruta("GET", "/{quien}", "saluda").unwrap();
    let s = Servidor::nuevo(router)
        .con(|r| {
            r.poner(Saludo("hola"));
        })
        .accion("saluda", |c: &Contexto| {
            let saludo = c.registro().obtener::<Saludo>()?;
            Ok(Respuesta::texto(&format!("{} {}", saludo.0, c.variable("quien").unwrap_or(""))))
        });
    assert_eq!(cuerpo(&s.responder(&peticion("GET", "/ana"), "x")), "hola ana", "RUT-037");
}

/// `OBS-012`: el log de acceso lleva el estado **finalmente enviado**, no el que la respuesta
/// tenía antes de fallar. Sale de un caso real: la vista reventaba después de fijar el 200, el
/// cliente recibía un 500 y el log seguía diciendo 200. Un log que dice lo contrario de lo que
/// pasó es peor que no tener log, porque se le hace caso.
#[test]
fn obs_012_el_log_lleva_el_estado_que_de_verdad_salio() {
    let s = servidor();
    let r = s.responder(&peticion("GET", "/estalla"), "x");
    assert_eq!(r.estado, 500);
    let acceso = s.log().lineas().into_iter().find(|l| l.contains("/estalla") && l.contains("GET"));
    assert!(acceso.expect("hay línea de acceso").contains("500"), "OBS-012");
}

/// `OBS-023`: lo declarado como ignorado no se registra. El caso que lo pide es la sonda de salud,
/// que en un orquestador entra cada pocos segundos y ahoga todo lo demás.
#[test]
fn obs_023_lo_ignorado_no_se_registra() {
    let s = servidor().sin_registrar(&["/a"]);
    s.responder(&peticion("GET", "/a"), "x");
    s.responder(&peticion("GET", "/objeto"), "x");
    let acceso: Vec<String> = s.log().lineas();
    assert!(!acceso.iter().any(|l| l.contains("GET /a ")), "OBS-023: {acceso:?}");
    assert!(acceso.iter().any(|l| l.contains("/objeto")), "OBS-023: y lo demás sí");
}
