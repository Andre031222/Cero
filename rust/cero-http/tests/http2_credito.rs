//! El crédito de salida: `H2-040` y `H2-041`.
//!
//! Se prueba sin socket, igual que la sesión, pero por el motivo contrario: aquí lo que importa es
//! que varios hilos se repartan una cuenta común, y un servidor de verdad por medio solo añadiría
//! ruido a lo que ya es la parte difícil.

use std::sync::Arc;
use std::time::Duration;

use cero_http::http2::Credito;

#[test]
fn h2_040_no_se_manda_mas_de_lo_que_cabe() {
    let c = Credito::nuevo(100);
    c.abrir(1);
    assert_eq!(c.reservar(1, 250), Some(100), "H2-040: se recorta a la ventana del flujo");
    assert_eq!(c.ventana_de(1), Some(0));
    assert_eq!(c.ventana_de(0), Some(65_435), "y se descuenta también de la conexión");
}

/// La ventana de la conexión es común: un flujo con crédito de sobra se queda igual esperando si el
/// que se agotó es el de la conexión entera.
#[test]
fn h2_040_la_ventana_de_la_conexion_manda_sobre_la_del_flujo() {
    let c = Credito::nuevo(1_000_000);
    c.abrir(1);
    assert_eq!(c.reservar(1, 100_000), Some(65_535), "el tope es la conexión");
    assert_eq!(c.ventana_de(0), Some(0));
}

/// La de la conexión empieza en 65 535 pase lo que pase: `SETTINGS_INITIAL_WINDOW_SIZE` es por
/// flujo, y solo un WINDOW_UPDATE la mueve (§6.9.2). Confundirlo manda de más desde la primera
/// respuesta.
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

/// Un incremento de un flujo que ya se fue se ignora en vez de cortar: el WINDOW_UPDATE pudo
/// cruzarse por el cable con el RST_STREAM, y eso no es culpa de nadie.
#[test]
fn un_incremento_de_un_flujo_que_ya_no_esta_no_molesta() {
    let c = Credito::nuevo(10);
    assert!(c.ampliar(7, 1_000));
}

/// §6.9.2: la ventana inicial nueva mueve la de los flujos ya abiertos y la de los que vengan.
#[test]
fn reajustar_mueve_los_abiertos_y_los_siguientes() {
    let c = Credito::nuevo(100);
    c.abrir(1);
    c.reservar(1, 40);
    assert!(c.reajustar(-50));
    assert_eq!(c.ventana_de(1), Some(10), "60 que quedaban, menos 50");
    c.abrir(3);
    assert_eq!(c.ventana_de(3), Some(50), "y el flujo nuevo arranca con la nueva");
}

/// Una ventana puede quedar **negativa** por un reajuste, y eso es correcto: el cliente redujo la
/// inicial después de que le mandáramos. Tratarlo como error cortaría una conexión sana.
#[test]
fn una_ventana_negativa_por_reajuste_no_es_un_error() {
    let c = Credito::nuevo(100);
    c.abrir(1);
    c.reservar(1, 100);
    assert!(c.reajustar(-50));
    assert_eq!(c.ventana_de(1), Some(-50));
    assert!(c.ampliar(1, 60));
    assert_eq!(c.reservar(1, 100), Some(10), "hasta que no vuelve a ser positiva no sale nada");
}

/// Lo que hace que esto sea control de flujo y no un recorte: sin crédito se **espera**, y se
/// despierta cuando llega. Devolver 0 y seguir sería un bucle de espera activa.
#[test]
fn h2_040_sin_credito_se_espera_hasta_que_llega() {
    let c = Arc::new(Credito::nuevo(10));
    c.abrir(1);
    c.reservar(1, 10);

    let suyo = Arc::clone(&c);
    let hilo = std::thread::spawn(move || suyo.reservar(1, 100));
    std::thread::sleep(Duration::from_millis(50));
    c.ampliar(1, 70);
    assert_eq!(hilo.join().unwrap(), Some(70));
}

/// Nadie se queda esperando un crédito que ya no va a llegar. Sin esto, el hilo que lee espera a
/// los de flujo al cerrar y ellos esperan a un cliente que ya se fue: la conexión no termina nunca.
#[test]
fn cerrar_y_anular_despiertan_al_que_esperaba() {
    for cerrar in [true, false] {
        let c = Arc::new(Credito::nuevo(0));
        c.abrir(1);
        let suyo = Arc::clone(&c);
        let hilo = std::thread::spawn(move || suyo.reservar(1, 10));
        std::thread::sleep(Duration::from_millis(50));
        if cerrar { c.cerrar() } else { c.anular(1) }
        assert_eq!(hilo.join().unwrap(), None);
    }
}
