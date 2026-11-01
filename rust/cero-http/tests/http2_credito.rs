use std::sync::Arc;
use std::time::{Duration, Instant};

use cero_http::http2::Credito;

fn siempre() -> Instant {
    Instant::now() + Duration::from_secs(3600)
}

#[test]
fn h2_040_no_se_manda_mas_de_lo_que_cabe() {
    let c = Credito::nuevo(100);
    c.abrir(1);
    assert_eq!(c.reservar(1, 250, siempre()), Some(100), "H2-040: se recorta a la ventana del flujo");
    assert_eq!(c.ventana_de(1), Some(0));
    assert_eq!(c.ventana_de(0), Some(65_435), "y se descuenta también de la conexión");
}

#[test]
fn h2_040_la_ventana_de_la_conexion_manda_sobre_la_del_flujo() {
    let c = Credito::nuevo(1_000_000);
    c.abrir(1);
    assert_eq!(c.reservar(1, 100_000, siempre()), Some(65_535), "el tope es la conexión");
    assert_eq!(c.ventana_de(0), Some(0));
}

#[test]
fn la_ventana_de_la_conexion_no_la_fijan_los_ajustes() {
    assert_eq!(Credito::nuevo(1_000_000).ventana_de(0), Some(65_535));
}

#[test]
fn h2_041_una_ventana_por_encima_del_tope_se_rechaza() {
    let c = Credito::nuevo(65_535);
    c.abrir(1);
    assert!(c.ampliar(1, 0x7fff_ffff - 65_535), "justo en el tope, cabe");
    assert!(!c.ampliar(1, 1), "H2-041: uno más, no");
    assert!(!c.ampliar(0, 0x7fff_ffff), "H2-041: y lo mismo en la conexión");
}

#[test]
fn un_incremento_de_un_flujo_que_ya_no_esta_no_molesta() {
    let c = Credito::nuevo(10);
    assert!(c.ampliar(7, 1_000));
}

#[test]
fn reajustar_mueve_los_abiertos_y_los_siguientes() {
    let c = Credito::nuevo(100);
    c.abrir(1);
    c.reservar(1, 40, siempre());
    assert!(c.reajustar(-50));
    assert_eq!(c.ventana_de(1), Some(10), "60 que quedaban, menos 50");
    c.abrir(3);
    assert_eq!(c.ventana_de(3), Some(50), "y el flujo nuevo arranca con la nueva");
}

#[test]
fn una_ventana_negativa_por_reajuste_no_es_un_error() {
    let c = Credito::nuevo(100);
    c.abrir(1);
    c.reservar(1, 100, siempre());
    assert!(c.reajustar(-50));
    assert_eq!(c.ventana_de(1), Some(-50));
    assert!(c.ampliar(1, 60));
    assert_eq!(c.reservar(1, 100, siempre()), Some(10), "hasta que no vuelve a ser positiva no sale nada");
}

#[test]
fn h2_040_sin_credito_se_espera_hasta_que_llega() {
    let c = Arc::new(Credito::nuevo(10));
    c.abrir(1);
    c.reservar(1, 10, siempre());

    let suyo = Arc::clone(&c);
    let hilo = std::thread::spawn(move || suyo.reservar(1, 100, siempre()));
    std::thread::sleep(Duration::from_millis(50));
    c.ampliar(1, 70);
    assert_eq!(hilo.join().unwrap(), Some(70));
}

#[test]
fn cerrar_y_anular_despiertan_al_que_esperaba() {
    for cerrar in [true, false] {
        let c = Arc::new(Credito::nuevo(0));
        c.abrir(1);
        let suyo = Arc::clone(&c);
        let hilo = std::thread::spawn(move || suyo.reservar(1, 10, siempre()));
        std::thread::sleep(Duration::from_millis(50));
        if cerrar { c.cerrar() } else { c.anular(1) }
        assert_eq!(hilo.join().unwrap(), None);
    }
}

#[test]
fn h2_052_quien_espera_credito_tiene_plazo() {
    let c = Credito::nuevo(0);
    c.abrir(1);
    let empezo = Instant::now();
    assert_eq!(c.reservar(1, 10, empezo + Duration::from_millis(100)), None, "H2-052: sin crédito en el plazo, nada");
    assert!(empezo.elapsed() < Duration::from_secs(1), "H2-052: y no se espera más");
}
