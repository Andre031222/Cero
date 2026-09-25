//! `H2-048`: un error de conexión tiene que poder leerse.
//!
//! Esta sí necesita un socket, y es la única de HTTP/2 que lo necesita: lo que se comprueba es
//! justo lo que pasa **entre** escribir el GOAWAY y cerrar. Con la sesión a secas no se ve, porque
//! ahí el GOAWAY se escribe siempre bien; el fallo estaba en el cierre.

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::time::Duration;

use cero_http::http2::{self, Ajustes, Trama, PREAMBULO};
use cero_http::{Respuesta, Peticion};

/// Levanta el conductor contra un socket y devuelve la punta del cliente.
fn conexion() -> TcpStream {
    let oyente = TcpListener::bind("127.0.0.1:0").expect("puerto");
    let puerto = oyente.local_addr().unwrap().port();
    std::thread::spawn(move || {
        let (socket, _) = oyente.accept().expect("conexión");
        let atender = std::sync::Arc::new(|_: Peticion| Respuesta::texto("ok"));
        let _ = http2::conexion::servir(socket, Ajustes::default(), atender);
    });
    let cliente = TcpStream::connect(("127.0.0.1", puerto)).expect("conectar");
    cliente.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
    cliente
}

/// Cerrar un socket con octetos sin leer en el búfer de recepción no manda un FIN, manda un RST, y
/// un RST borra en la otra punta lo que todavía no había leído. El GOAWAY se escribía bien y se
/// perdía siempre: el cliente veía «connection reset by peer» y nunca el motivo.
///
/// Se provoca con una trama que declara más carga de la que el servidor admite leer, porque así
/// quedan octetos sin consumir en el búfer — que es la condición exacta del fallo.
#[test]
fn h2_048_el_goaway_se_puede_leer_despues_de_cerrar() {
    let mut cliente = conexion();
    cliente.write_all(PREAMBULO).unwrap();
    Ajustes::default().trama().escribir(&mut cliente).unwrap();

    // Una trama de 20 000 octetos cuando el máximo anunciado son 16 384: FRAME_SIZE_ERROR.
    let mut cabecera = vec![0x00, 0x4e, 0x20, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01];
    cabecera.extend(std::iter::repeat_n(0u8, 20_000));
    let _ = cliente.write_all(&cabecera);

    let mut leido = Vec::new();
    cliente.read_to_end(&mut leido).expect("H2-048: se lee hasta el fin, no un reinicio");

    let goaway = tramas(&leido).into_iter().find(|t| t.tipo == http2::trama::GOAWAY);
    let goaway = goaway.expect("H2-048: el GOAWAY llegó entero");
    let codigo = u32::from_be_bytes([goaway.carga[4], goaway.carga[5], goaway.carga[6], goaway.carga[7]]);
    assert_eq!(codigo, http2::Error::TamanoDeTrama as u32, "H2-048: y dice por qué");
}

fn tramas(mut octetos: &[u8]) -> Vec<Trama> {
    let mut salida = Vec::new();
    while octetos.len() >= 9 {
        let largo = u32::from_be_bytes([0, octetos[0], octetos[1], octetos[2]]) as usize;
        if octetos.len() < 9 + largo {
            break;
        }
        salida.push(Trama {
            tipo: octetos[3],
            banderas: octetos[4],
            flujo: u32::from_be_bytes([octetos[5] & 0x7f, octetos[6], octetos[7], octetos[8]]),
            carga: octetos[9..9 + largo].to_vec(),
        });
        octetos = &octetos[9 + largo..];
    }
    salida
}
