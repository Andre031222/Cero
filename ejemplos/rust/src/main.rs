use cero_http::{Json, Respuesta, Servidor};
use std::{env, io};

fn main() -> io::Result<()> {
    let puerto = env::var("PUERTO").ok().and_then(|p| p.parse().ok()).unwrap_or(8080);
    Servidor::nuevo_vacio()
        .get("/", |_| Respuesta::json(Json::objeto(vec![("hola", "mundo".into())])))
        .get("/saludo/{nombre}", |c| {
            let nombre = c.variable("nombre").unwrap_or("mundo");
            Respuesta::json(Json::objeto(vec![("saludo", format!("hola, {nombre}").into())]))
        })
        .post("/eco", |c| Ok(Respuesta::json(c.cuerpo_json()?)))
        .escuchar(puerto)
}
