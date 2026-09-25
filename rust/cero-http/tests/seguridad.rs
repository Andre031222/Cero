//! Una prueba por requisito de `spec/seguridad.md`, citándolo.

use cero_http::seguridad::*;
use std::time::Duration;

fn tiene<'a>(h: &'a [(String, String)], n: &str) -> Option<&'a str> {
    h.iter().find(|(k, _)| k == n).map(|(_, v)| v.as_str())
}

// ── Cabeceras ───────────────────────────────────────────────────────────────

#[test]
fn seg_001_a_004_las_cabeceras_por_defecto() {
    let h = Cabeceras::default().aplicar(false);
    assert_eq!(tiene(&h, "X-Content-Type-Options"), Some("nosniff"), "SEG-001");
    assert_eq!(tiene(&h, "X-Frame-Options"), Some("DENY"), "SEG-002");
    assert!(tiene(&h, "Referrer-Policy").is_some(), "SEG-003");
    let p = tiene(&h, "Permissions-Policy").expect("SEG-004");
    for capacidad in ["camera=()", "microphone=()", "geolocation=()"] {
        assert!(p.contains(capacidad), "SEG-004: falta {capacidad}");
    }
}

#[test]
fn seg_005_sin_tls_no_se_manda_hsts() {
    assert!(tiene(&Cabeceras::default().aplicar(false), "Strict-Transport-Security").is_none(),
            "SEG-005");
    assert!(tiene(&Cabeceras::default().aplicar(true), "Strict-Transport-Security").is_some(),
            "SEG-005: con TLS sí");
}

#[test]
fn seg_006_sin_csp_declarada_no_se_inventa() {
    assert!(tiene(&Cabeceras::default().aplicar(true), "Content-Security-Policy").is_none(),
            "SEG-006");
    let c = Cabeceras { csp: Some("default-src 'self'".into()), ..Default::default() };
    assert_eq!(tiene(&c.aplicar(true), "Content-Security-Policy"), Some("default-src 'self'"),
               "SEG-007: declararla es posible");
}

#[test]
fn seg_007_el_enmarcado_se_puede_aflojar_sin_tocar_el_resto() {
    let c = Cabeceras { enmarcado: "SAMEORIGIN", ..Default::default() };
    let h = c.aplicar(false);
    assert_eq!(tiene(&h, "X-Frame-Options"), Some("SAMEORIGIN"), "SEG-007");
    assert_eq!(tiene(&h, "X-Content-Type-Options"), Some("nosniff"), "SEG-007: el resto sigue");
}

// ── CORS ────────────────────────────────────────────────────────────────────

fn cors(origenes: Origenes, credenciales: bool) -> Cors {
    Cors { origenes, credenciales,
           metodos: vec!["GET".into(), "POST".into()],
           cabeceras: vec!["Content-Type".into()], max_age: 600 }
}

#[test]
fn seg_008_y_009_origen_permitido() {
    let c = cors(Origenes::Lista(vec!["https://a.pe".into()]), false);
    let Decision::Sigue(h) = c.decidir("GET", Some("https://a.pe")) else { panic!("sigue") };
    assert_eq!(tiene(&h, "Access-Control-Allow-Origin"), Some("https://a.pe"), "SEG-008");
    assert_eq!(tiene(&h, "Vary"), Some("Origin"), "SEG-009");
}

#[test]
fn seg_010_el_origen_ajeno_no_bloquea_la_peticion_simple() {
    let c = cors(Origenes::Lista(vec!["https://a.pe".into()]), false);
    let Decision::Sigue(h) = c.decidir("GET", Some("https://malo.pe")) else {
        panic!("SEG-010: la petición simple no se bloquea");
    };
    assert!(tiene(&h, "Access-Control-Allow-Origin").is_none(), "SEG-010: pero no recibe permiso");
}

#[test]
fn seg_011_sin_origin_no_se_anade_nada() {
    let c = cors(Origenes::Cualquiera, false);
    let Decision::Sigue(h) = c.decidir("GET", None) else { panic!("sigue") };
    assert!(h.is_empty(), "SEG-011");
}

#[test]
fn seg_012_el_preflight_admitido_responde_204_y_anuncia() {
    let c = cors(Origenes::Lista(vec!["https://a.pe".into()]), false);
    let Decision::Corta(estado, h) = c.decidir("OPTIONS", Some("https://a.pe")) else {
        panic!("SEG-012: el preflight corta");
    };
    assert_eq!(estado, 204, "SEG-012");
    assert!(tiene(&h, "Access-Control-Allow-Methods").is_some(), "SEG-012: métodos");
    assert!(tiene(&h, "Access-Control-Allow-Headers").is_some(), "SEG-012: cabeceras");
    assert_eq!(tiene(&h, "Access-Control-Max-Age"), Some("600"), "SEG-012: caché");
}

#[test]
fn seg_013_el_preflight_ajeno_da_403() {
    let c = cors(Origenes::Lista(vec!["https://a.pe".into()]), false);
    let Decision::Corta(estado, _) = c.decidir("OPTIONS", Some("https://malo.pe")) else {
        panic!("SEG-013: el preflight ajeno corta");
    };
    assert_eq!(estado, 403, "SEG-013");
}

#[test]
fn seg_014_comodin_solo_sin_credenciales() {
    let sin = cors(Origenes::Cualquiera, false);
    let Decision::Sigue(h) = sin.decidir("GET", Some("https://x.pe")) else { panic!() };
    assert_eq!(tiene(&h, "Access-Control-Allow-Origin"), Some("*"), "SEG-014");

    let con = cors(Origenes::Cualquiera, true);
    let Decision::Sigue(h) = con.decidir("GET", Some("https://x.pe")) else { panic!() };
    assert_eq!(tiene(&h, "Access-Control-Allow-Origin"), Some("https://x.pe"),
               "SEG-014: con credenciales, el origen concreto");
    assert_eq!(tiene(&h, "Access-Control-Allow-Credentials"), Some("true"), "SEG-014");
}

// ── CSRF ────────────────────────────────────────────────────────────────────

#[test]
fn seg_015_a_018_el_token_manda_en_lo_que_escribe() {
    let sin = Vec::new();
    assert!(csrf_valido("GET", "/x", &sin, Some("t"), None), "SEG-015: seguro pasa sin token");
    assert!(!csrf_valido("POST", "/x", &sin, Some("t"), None), "SEG-016: sin token no pasa");
    assert!(csrf_valido("POST", "/x", &sin, Some("t"), Some("t")), "SEG-018: válido pasa");
    assert!(!csrf_valido("POST", "/x", &sin, Some("t"), Some("otro")), "SEG-018: erróneo no");
}

#[test]
fn seg_019_la_exencion_casa_por_segmento_completo() {
    let ex = vec!["/api/publico".to_string()];
    assert!(exento("/api/publico", &ex), "SEG-019");
    assert!(exento("/api/publico/algo", &ex), "SEG-019: y lo que cuelga de él");
    // El hallazgo de auditoría: por prefijo pelado, esto quedaba exento sin quererlo.
    assert!(!exento("/api/publicoSECRETO", &ex), "SEG-019: NO por prefijo pelado");
}

// ── Límite ──────────────────────────────────────────────────────────────────

#[test]
fn seg_020_y_021_el_429_anuncia_lo_que_debe() {
    let l = Limitador::nuevo(3, Duration::from_secs(60));
    for _ in 0..3 {
        assert!(l.pedir("1.2.3.4").permitida, "SEG-021: dentro del cupo pasa");
    }
    let v = l.pedir("1.2.3.4");
    assert!(!v.permitida, "SEG-020: pasado el cupo, no");
    assert_eq!(v.restante, 0, "SEG-020: sin cupo restante");
    let h = cabeceras_limite(&v);
    assert!(tiene(&h, "Retry-After").is_some(), "SEG-020: con Retry-After");
    assert_eq!(tiene(&h, "X-RateLimit-Limit"), Some("3"), "SEG-021");
}

/// SEG-022 no se verifica aquí, y conviene decir por qué en vez de fingir que sí.
///
/// El requisito dice que la cuota no puede depender de la ruta. En Java eso es comprobable:
/// `pedir` recibía la ruta y había que verificar que no la usara. Aquí `pedir` **no la recibe**,
/// así que el incumplimiento no se puede escribir: no hay prueba que pueda fallar.
///
/// Es la misma clase de hallazgo que `SES-011` en el hito 2. Parte del contrato se cumple por
/// disciplina y parte se puede delegar a la forma del tipo; cuál es cuál depende del lenguaje, y
/// donde se delega, la prueba deja de tener sentido y sobra.
///
/// Lo que sí se comprueba es la consecuencia que el hallazgo de auditoría tenía: que el mapa de
/// cuentas no crece con el tráfico de un mismo cliente.
#[test]
fn seg_022_el_mapa_de_cuotas_no_crece_con_las_peticiones() {
    let l = Limitador::nuevo(2, Duration::from_secs(60));
    for _ in 0..50 {
        l.pedir("1.2.3.4");
    }
    assert_eq!(l.claves(), 1, "SEG-022: una clave por cliente, no por petición");
    assert!(!l.pedir("1.2.3.4").permitida, "y el cupo se agota igual");
}

// ── Saneado ─────────────────────────────────────────────────────────────────

#[test]
fn seg_023_quita_lo_ejecutable() {
    for malo in ["<script>alert(1)</script>", "<style>x</style>", "<iframe src=x></iframe>"] {
        let limpio = sanear_html(malo);
        assert!(!limpio.to_lowercase().contains("script"), "SEG-023: {malo} → {limpio}");
        assert!(!limpio.to_lowercase().contains("iframe"), "SEG-023: {malo} → {limpio}");
    }
    assert!(!sanear_html("<img src=x onerror=alert(1)>").contains("onerror"),
            "SEG-023: manejadores de evento");
    assert!(!sanear_html("<a href=\"javascript:alert(1)\">x</a>").contains("javascript:"),
            "SEG-023: el protocolo javascript");
}

#[test]
fn seg_024_conserva_el_marcado_inocuo() {
    let limpio = sanear_html("<p>hola <b>mundo</b></p>");
    assert!(limpio.contains("<p>") && limpio.contains("<b>"), "SEG-024: {limpio}");
    assert!(limpio.contains("hola") && limpio.contains("mundo"), "SEG-024");
}

#[test]
fn seg_025_a_texto_no_queda_ni_rastro() {
    assert_eq!(sanear_texto("<p>hola</p>").trim(), "hola", "SEG-025");
    let s = sanear_texto("<script>alert(1)</script>visible");
    assert!(!s.contains("alert"), "SEG-025: del script no queda rastro · {s}");
    assert!(s.contains("visible"), "SEG-025: lo de fuera sí");
}

#[test]
fn seg_026_los_nombres_de_archivo() {
    assert_eq!(sanear_nombre("/etc/passwd"), "passwd", "SEG-026: quita la ruta");
    assert_eq!(sanear_nombre("C:\\Windows\\x.txt"), "x.txt", "SEG-026: y la de Windows");
    assert_eq!(sanear_nombre("informe final.pdf"), "informe final.pdf", "SEG-026: lo normal pasa");
    assert!(!sanear_nombre("...").is_empty(), "SEG-026: nunca queda vacío");
    // El intento real: colar una cookie por el nombre de archivo.
    assert!(!sanear_nombre("a\"; Set-Cookie: x=y").contains(';'), "SEG-026");
}

// ── El token CSRF, de punta a punta ─────────────────────────────────────────

/// `SEG-017`: la respuesta que exige token tiene que poder emitir uno, y ese token tiene que estar
/// atado a una sesión. Sin lo primero una ruta protegida es inusable —el cliente no tiene de dónde
/// sacar el token—; sin lo segundo no protege de nada, porque el sitio atacante pediría el suyo.
///
/// Emitirlo lo pide la acción y no lo pone el framework en toda respuesta: hacerlo siempre
/// obligaría a abrir sesión a todo el que pase, incluido el rastreador que nunca va a mandar un
/// POST. Es `SES-001` otra vez.
#[test]
fn seg_017_el_token_se_emite_y_lo_ata_una_sesion() {
    use cero_http::{Contexto, Peticion, Respuesta, Router, Servidor};

    let router = Router::nuevo()
        .ruta("GET", "/formulario", "formulario").unwrap()
        .ruta("POST", "/guardar", "guardar").unwrap();
    let s = Servidor::nuevo(router)
        .csrf(&[])
        .accion("formulario", |c: &Contexto| {
            Respuesta::texto(&c.token_csrf().unwrap_or_default())
        })
        .accion("guardar", |_: &Contexto| Respuesta::texto("guardado"));

    let pedir = |metodo: &str, destino: &str, cabeceras: Vec<(&str, &str)>| {
        let mut p = Peticion {
            metodo: metodo.into(),
            destino: destino.into(),
            version: "HTTP/1.1".into(),
            cabeceras: [("host".to_string(), "x".to_string())].into_iter().collect(),
            cuerpo: Vec::new(),
        };
        for (n, v) in cabeceras {
            p.cabeceras.insert(n.into(), v.into());
        }
        s.responder(&p, "x")
    };

    let formulario = pedir("GET", "/formulario", vec![]);
    let token = String::from_utf8_lossy(&formulario.cuerpo).into_owned();
    assert!(token.len() >= 40, "SEG-017: hay token, y no es simbólico");

    let cookie = formulario.extra.iter().find(|(k, _)| k == "Set-Cookie")
        .map(|(_, v)| v.clone()).expect("SEG-017: y una sesión que lo ate");
    let id = cookie.split(';').next().unwrap().to_string();

    // SEG-016: el mismo token sin la sesión no vale, que es lo que hace que atarlo sirva.
    assert_eq!(pedir("POST", "/guardar", vec![("x-csrf-token", &token)]).estado, 403, "SEG-016");
    assert_eq!(pedir("POST", "/guardar", vec![("cookie", &id)]).estado, 403, "SEG-016");
    // SEG-018: los dos juntos pasan.
    let bien = pedir("POST", "/guardar", vec![("cookie", &id), ("x-csrf-token", &token)]);
    assert_eq!(bien.estado, 200, "SEG-018");
}

// ── Validación · SEG-027 y SEG-028 ──────────────────────────────────────────

/// `SEG-027`: validar no es normalizar. Si la validación devolviera algo retocado —un texto
/// recortado, un número redondeado—, lo que llega a la acción ya no sería lo que mandó el cliente,
/// y eso es una transformación que nadie pidió escondida detrás de una comprobación.
#[test]
fn seg_027_un_cuerpo_valido_llega_sin_alterar() {
    use cero_http::{json, validar, Regla};

    let crudo = r#"{"nombre":"  Ana  ","edad":30,"extra":[1,2]}"#;
    let cuerpo = json::leer(crudo).unwrap();
    let pasado = validar(cuerpo, &[
        ("nombre", Regla::Texto { minimo: 1, maximo: 50 }),
        ("edad", Regla::Entero { minimo: 0, maximo: 120 }),
    ]).expect("SEG-027: es válido");

    assert_eq!(pasado.get("nombre").and_then(json::Json::texto), Some("  Ana  "),
               "SEG-027: ni los espacios se tocan");
    assert!(pasado.get("extra").is_some(), "SEG-027: y lo que no se validó sigue ahí");
}

/// `SEG-028`: 422 y no 400 —el cuerpo se entendió, lo que falla es su contenido—, y con el campo y
/// el motivo. Un 422 que solo dice «inválido» obliga a quien llama a adivinar, y lo que hace en la
/// práctica es reintentar con lo mismo.
#[test]
fn seg_028_un_cuerpo_invalido_es_422_y_dice_cual() {
    use cero_http::{json, validar, Regla};

    let cuerpo = json::leer(r#"{"nombre":"","edad":300}"#).unwrap();
    let fallo = validar(cuerpo, &[
        ("nombre", Regla::Obligatorio),
        ("edad", Regla::Entero { minimo: 0, maximo: 120 }),
        ("apodo", Regla::Obligatorio),
    ]).unwrap_err();

    assert_eq!(fallo.estado, 422, "SEG-028: no 400");
    let campos = fallo.detalle.as_ref().and_then(|d| d.get("campos")).expect("SEG-028");
    assert_eq!(campos.get("nombre").and_then(json::Json::texto), Some("es obligatorio"),
               "SEG-028: presente y vacío es lo mismo que ausente");
    assert_eq!(campos.get("apodo").and_then(json::Json::texto), Some("es obligatorio"));
    assert!(campos.get("edad").and_then(json::Json::texto).unwrap().contains("120"),
            "SEG-028: y por qué, no solo cuál");
}
