use cero_http::{Respuesta, Router, Servidor};

fn main() -> std::io::Result<()> {
    let puerto: u16 = std::env::args().nth(1).and_then(|p| p.parse().ok()).unwrap_or(8777);
    let router = Router::nuevo()
        .ruta("GET", "/", "raiz").expect("patrón")
        .ruta("GET", "/eco", "eco").expect("patrón")
        .ruta("POST", "/eco", "eco").expect("patrón");
    Servidor::nuevo(router)
        .accion("raiz", |_| Respuesta::texto("cero, conforme\n"))
        .accion("eco", |c| Respuesta {
            cuerpo: c.peticion.cuerpo.clone(),
            ..Respuesta::texto("")
        })
        .escuchar(puerto)
}
