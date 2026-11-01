use cero_http::observabilidad::{Salud, Veredicto};
use cero_http::seguridad::{Cors, Origenes};
use cero_http::{Json, Respuesta, Router, Servidor};
use std::time::Duration;

fn main() -> std::io::Result<()> {
    let puerto: u16 = std::env::args().nth(1).and_then(|p| p.parse().ok()).unwrap_or(8080);

    let router = Router::nuevo()
        .ruta("GET", "/", "portada").expect("patrón")
        .ruta("GET", "/saludo/{nombre}", "saludo").expect("patrón")
        .ruta("GET", "/notas/{id}", "nota").expect("patrón")
        .ruta("GET", "/entrar", "entrar").expect("patrón")
        .ruta("GET", "/quien", "quien").expect("patrón")
        .ruta("POST", "/notas", "crear").expect("patrón")
        .ruta("GET", "/metricas", "metricas").expect("patrón");

    Servidor::nuevo(router)
        .cors(Cors {
            origenes: Origenes::Lista(vec!["https://cero.ginit.dev".into()]),
            credenciales: true,
            metodos: vec!["GET".into(), "POST".into()],
            cabeceras: vec!["Content-Type".into(), "X-CSRF-Token".into()],
            max_age: 600,
        })
        .limite(100, Duration::from_secs(60))
        .csrf(&["/hooks"])
        .salud(
            Salud::nueva()
                .comprobacion("memoria", || Veredicto::Bien)
                .comprobacion("disco", || Veredicto::Bien),
        )
        .accion("portada", |_| {
            Respuesta::html("<h1>Cero</h1><p>El mismo framework, en Rust.</p>")
        })
        .accion("saludo", |c| {
            Respuesta::texto(&format!("hola, {}", c.variable("nombre").unwrap_or("mundo")))
        })
        .accion("nota", |c| {
            let id = c.variable_como::<u32>("id")?;
            Ok(Respuesta::json(Json::objeto(vec![
                ("id", (id as i64).into()),
                ("titulo", format!("nota {id}").into()),
            ])))
        })
        .accion("entrar", |c| {
            let Some(s) = c.abrir_sesion() else {
                return Respuesta::estado(500, "no se pudo abrir la sesión");
            };
            let Ok(mut g) = s.lock() else {
                return Respuesta::estado(500, "sesión envenenada");
            };
            let _ = g.poner("quien", &c.consulta_o("como", "anónimo"));
            let _ = g.poner("csrf", "un-token-de-ejemplo");
            Respuesta::texto("sesión abierta")
        })
        .accion("quien", |c| match c.sesion() {
            Some(s) => {
                let quien = s
                    .lock()
                    .ok()
                    .and_then(|g| g.leer("quien").ok().flatten().cloned())
                    .unwrap_or_else(|| "desconocido".into());
                Respuesta::texto(&quien)
            }
            None => Respuesta::estado(401, "sin sesión"),
        })
        .accion("crear", |c| {
            let cuerpo = c.cuerpo_texto();
            if cuerpo.trim().is_empty() {
                return Respuesta {
                    estado: 422,
                    ..Respuesta::json(Json::objeto(vec![(
                        "campos",
                        Json::objeto(vec![("cuerpo", "no puede estar vacío".into())]),
                    )]))
                };
            }
            let titulo = c
                .cuerpo_json()
                .ok()
                .and_then(|j| j.get("titulo").and_then(|t| t.texto().map(str::to_string)))
                .or_else(|| c.campo("titulo"))
                .unwrap_or_else(|| "sin título".into());
            Respuesta::json(Json::objeto(vec![("creada", true.into()), ("titulo", titulo.into())]))
        })
        .accion("metricas", |_| Respuesta::json_crudo("{}"))
        .escuchar(puerto)
}
