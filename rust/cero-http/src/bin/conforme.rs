use std::io::{Error, ErrorKind};
use std::time::Duration;

use cero_http::{Plazos, Respuesta, Router, Servidor};

fn main() -> std::io::Result<()> {
    let mut args = std::env::args().skip(1);
    let puerto: u16 = args.next().and_then(|p| p.parse().ok()).unwrap_or(8777);
    let plazos = match args.next() {
        None => Plazos::default(),
        Some(ms) => {
            let ms: u64 = ms.parse().map_err(|_| Error::new(ErrorKind::InvalidInput, "plazo en ms"))?;
            let plazo = Duration::from_millis(ms);
            Plazos { sin_avance: plazo, escritura: plazo, inactividad: 2 * plazo }
        }
    };
    let router = Router::nuevo()
        .ruta("GET", "/", "raiz").expect("patrón")
        .ruta("GET", "/eco", "eco").expect("patrón")
        .ruta("POST", "/eco", "eco").expect("patrón")
        .ruta("GET", "/grande", "grande").expect("patrón");
    Servidor::nuevo(router)
        .accion("raiz", |_| Respuesta::texto("cero, conforme\n"))
        .accion("eco", |c| Respuesta {
            cuerpo: c.peticion.cuerpo.clone(),
            ..Respuesta::texto("")
        })
        .accion("grande", |_| Respuesta::texto(&"x".repeat(1 << 20)))
        .plazos(plazos)
        .escuchar(puerto)
}
