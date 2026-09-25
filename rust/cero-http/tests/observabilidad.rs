//! Una prueba por requisito de `spec/observabilidad.md`, citándolo.

use cero_http::observabilidad::*;
use std::time::Duration;

// ── Salud ───────────────────────────────────────────────────────────────────

#[test]
fn obs_001_vivo_responde_mientras_el_proceso_responda() {
    let s = Salud::nueva().comprobacion("bd", || Veredicto::Mal("caída".into()));
    let v = s.vivo();
    assert_eq!(v.estado, 200, "OBS-001: vivo no mira las comprobaciones");
    assert!(v.cuerpo.contains("activo_s"), "OBS-001: dice cuánto lleva en pie");
}

#[test]
fn obs_002_listo_con_todo_en_verde() {
    let s = Salud::nueva()
        .comprobacion("bd", || Veredicto::Bien)
        .comprobacion("cola", || Veredicto::Bien);
    let l = s.listo();
    assert_eq!(l.estado, 200, "OBS-002");
    assert!(l.cuerpo.contains("bd") && l.cuerpo.contains("cola"), "OBS-002: las nombra");
}

#[test]
fn obs_003_y_004_una_caida_baja_listo_pero_no_vivo() {
    let s = Salud::nueva()
        .comprobacion("bd", || Veredicto::Mal("sin conexión".into()))
        .comprobacion("cola", || Veredicto::Bien);
    let l = s.listo();
    assert_eq!(l.estado, 503, "OBS-003");
    assert_eq!(s.vivo().estado, 200, "OBS-003: vivo sigue en 200");
    assert!(l.cuerpo.contains("bd"), "OBS-004: dice cuál falló");
    assert!(l.cuerpo.contains("sin conexión"), "OBS-004: y por qué");
    assert!(l.cuerpo.contains("cola"), "OBS-004: sin ocultar las que sí van");
}

#[test]
fn obs_005_una_comprobacion_que_lanza_da_503() {
    let s = Salud::nueva().comprobacion("explota", || panic!("boom"));
    assert_eq!(s.listo().estado, 503, "OBS-005: 503, no 500");
}

#[test]
fn obs_006_en_modo_publico_no_se_revela_nada() {
    let mut s = Salud::nueva().comprobacion("bd-interna", || Veredicto::Mal("host secreto".into()));
    s.publico = true;
    let l = s.listo();
    assert_eq!(l.estado, 503, "OBS-006: el código no cambia");
    assert!(!l.cuerpo.contains("host secreto"), "OBS-006: ni el mensaje");
    assert!(!l.cuerpo.contains("bd-interna"), "OBS-006: ni el nombre");
    assert_eq!(s.vivo().estado, 200, "OBS-006: y vivo no cambia");
}

#[test]
fn obs_007_en_modo_publico_y_en_verde_solo_dice_que_esta_listo() {
    let mut s = Salud::nueva().comprobacion("bd-interna", || Veredicto::Bien);
    s.publico = true;
    let l = s.listo();
    assert_eq!(l.estado, 200, "OBS-007");
    assert!(!l.cuerpo.contains("bd-interna"), "OBS-007: sin la lista");
}

// ── Registro ────────────────────────────────────────────────────────────────

#[test]
fn obs_008_la_linea_lleva_nivel_origen_y_valores() {
    let log = Log::nuevo("app", Nivel::Info);
    log.escribir(Nivel::Error, "falló {} con {}", &["la carga", "42"]);
    let l = &log.lineas()[0];
    assert!(l.contains("Error"), "OBS-008: el nivel");
    assert!(l.contains("app"), "OBS-008: el origen");
    assert!(l.contains("la carga") && l.contains("42"), "OBS-008: los valores");
}

#[test]
fn obs_009_el_nivel_filtra_y_hay_uno_que_calla_todo() {
    let log = Log::nuevo("app", Nivel::Aviso);
    log.escribir(Nivel::Info, "no debería salir", &[]);
    log.escribir(Nivel::Error, "sí debería", &[]);
    assert_eq!(log.lineas().len(), 1, "OBS-009: filtra por debajo");

    let mudo = Log::nuevo("app", Nivel::Nada);
    mudo.escribir(Nivel::Error, "nada", &[]);
    assert!(mudo.lineas().is_empty(), "OBS-009: NADA calla todo");
}

#[test]
fn obs_011_interpolar_nunca_lanza() {
    assert_eq!(interpolar("faltan {} y {}", &["uno"]), "faltan uno y {}", "OBS-011: de menos");
    assert_eq!(interpolar("sobra {}", &["uno", "dos"]), "sobra uno", "OBS-011: de más");
    assert_eq!(interpolar("sin marcadores", &["x"]), "sin marcadores", "OBS-011");
    assert_eq!(interpolar("", &[]), "", "OBS-011: vacío");
}

// ── Métricas ────────────────────────────────────────────────────────────────

#[test]
fn obs_013_y_016_cuenta_peticiones_y_errores() {
    let m = Metricas::nuevas();
    m.anotar("/a", 200, Duration::from_millis(1));
    m.anotar("/a", 500, Duration::from_millis(1));
    m.anotar("/a", 404, Duration::from_millis(1));
    assert_eq!(m.total(), 3, "OBS-013");
    assert_eq!(m.errores("/a"), 2, "OBS-016: el 404 cuenta como error");
}

#[test]
fn obs_014_agrupa_por_patron_y_no_por_url() {
    let m = Metricas::nuevas();
    // Dos identificadores distintos, un solo patrón. Con la URL como clave serían dos series, y
    // con mil identificadores, mil.
    m.anotar("/usuarios/{id}", 200, Duration::from_millis(1));
    m.anotar("/usuarios/{id}", 200, Duration::from_millis(1));
    assert_eq!(m.patrones(), 1, "OBS-014: una sola serie");
    assert_eq!(m.peticiones("/usuarios/{id}"), 2, "OBS-014: con las dos llamadas");
}

#[test]
fn obs_015_percentiles_y_no_solo_la_media() {
    let m = Metricas::nuevas();
    for ms in [1u64, 1, 1, 1, 1, 1, 1, 1, 1, 500] {
        m.anotar("/lenta", 200, Duration::from_millis(ms));
    }
    let p50 = m.percentil("/lenta", 0.50).expect("OBS-015");
    let p99 = m.percentil("/lenta", 0.99).expect("OBS-015");
    assert!(p50 < 10_000, "OBS-015: la mediana no ve la cola · {p50}µs");
    assert!(p99 > 100_000, "OBS-015: el p99 sí · {p99}µs");
}

#[test]
fn obs_017_las_ignoradas_no_se_cuentan() {
    let m = Metricas::nuevas();
    m.ignorar("/salud");
    m.anotar("/salud", 200, Duration::from_millis(1));
    m.anotar("/a", 200, Duration::from_millis(1));
    assert_eq!(m.total(), 1, "OBS-017");
    assert_eq!(m.patrones(), 1, "OBS-017: ni aparece como serie");
}

#[test]
fn obs_018_hay_exposicion_legible_por_maquina() {
    let m = Metricas::nuevas();
    m.anotar("/a", 200, Duration::from_millis(1));
    let j = m.json();
    assert!(j.contains("\"total\":1"), "OBS-018: el total · {j}");
    assert!(j.contains("\"ruta\":\"/a\""), "OBS-018: y el detalle por ruta");
}

// ── Log de acceso ───────────────────────────────────────────────────────────

#[test]
fn obs_019_a_021_la_linea_de_acceso() {
    let l = linea_acceso("GET", "/a?q=1", 200, Some("andre"), Duration::from_millis(7));
    assert!(l.contains("GET") && l.contains("200"), "OBS-019: verbo y estado");
    assert!(l.contains("q=1"), "OBS-020: conserva la cadena de consulta");
    assert!(l.contains("andre"), "OBS-021: el usuario");

    let anon = linea_acceso("GET", "/a", 200, None, Duration::from_millis(1));
    assert!(anon.contains(" - "), "OBS-021: el anónimo va con marca, no con un hueco");
}

#[test]
fn obs_022_se_registran_tambien_los_errores() {
    for estado in [400u16, 404, 500] {
        let l = linea_acceso("POST", "/x", estado, None, Duration::from_millis(1));
        assert!(l.contains(&estado.to_string()), "OBS-022: {estado}");
    }
}
