//! El bucle de aceptación.
//!
//! Un hilo del sistema por conexión. Java usa un hilo virtual, que está en la plataforma; la
//! biblioteca estándar de Rust no tiene equivalente y cualquier runtime async es una dependencia,
//! que es justo lo que este proyecto no admite. El contrato no habla de hilos, así que se cumple
//! igual — pero el modelo de concurrencia **no** es el mismo, y eso está contado en LEEME.md.

use crate::peticion::{self, Peticion, Rechazo};
use crate::ruta::{Resolucion, Router};
use std::io::Write;
use std::net::{TcpListener, TcpStream};
use std::sync::Arc;

pub struct Respuesta {
    pub estado: u16,
    pub tipo: String,
    pub cuerpo: Vec<u8>,
    pub extra: Vec<(String, String)>,
}

impl Respuesta {
    pub fn texto(cuerpo: &str) -> Respuesta {
        Respuesta {
            estado: 200,
            tipo: "text/plain; charset=utf-8".into(),
            cuerpo: cuerpo.as_bytes().to_vec(),
            extra: Vec::new(),
        }
    }

    pub fn estado(estado: u16, cuerpo: &str) -> Respuesta {
        Respuesta { estado, ..Respuesta::texto(cuerpo) }
    }
}

type Accion = Box<dyn Fn(&Peticion) -> Respuesta + Send + Sync>;

pub struct Servidor {
    router: Router,
    acciones: std::collections::HashMap<String, Accion>,
}

impl Servidor {
    pub fn nuevo(router: Router) -> Servidor {
        Servidor { router, acciones: std::collections::HashMap::new() }
    }

    pub fn accion(
        mut self,
        nombre: &str,
        f: impl Fn(&Peticion) -> Respuesta + Send + Sync + 'static,
    ) -> Servidor {
        self.acciones.insert(nombre.to_string(), Box::new(f));
        self
    }

    pub fn escuchar(self, puerto: u16) -> std::io::Result<()> {
        let oyente = TcpListener::bind(("0.0.0.0", puerto))?;
        let yo = Arc::new(self);
        for conexion in oyente.incoming() {
            let Ok(flujo) = conexion else { continue };
            let yo = Arc::clone(&yo);
            std::thread::spawn(move || yo.atender(flujo));
        }
        Ok(())
    }

    fn atender(&self, mut flujo: TcpStream) {
        // Una conexión sirve varias peticiones mientras el cliente no diga lo contrario.
        loop {
            let respuesta = match peticion::leer(&flujo) {
                Ok(p) => {
                    let cerrar = p.cabecera("connection").is_some_and(|c| c.eq_ignore_ascii_case("close"))
                        || p.version == "HTTP/1.0";
                    let solo_cabeceras = p.metodo == "HEAD";
                    let r = self.despachar(&p);
                    if escribir(&mut flujo, r, solo_cabeceras, cerrar).is_err() || cerrar {
                        return;
                    }
                    continue;
                }
                Err(Rechazo::MalFormada(_)) if flujo.peer_addr().is_err() => return,
                Err(motivo) => Respuesta::estado(motivo.estado(), ""),
            };
            let _ = escribir(&mut flujo, respuesta, false, true);
            return;
        }
    }

    fn despachar(&self, p: &Peticion) -> Respuesta {
        // RUT-003 del contrato de conformidad: asterisk-form solo vale para OPTIONS.
        if p.destino == "*" {
            return if p.metodo == "OPTIONS" {
                Respuesta::estado(200, "")
            } else {
                Respuesta::estado(400, "")
            };
        }
        match self.router.resolver(&p.metodo, p.camino()) {
            Resolucion::Encontrada(nombre, _) => match self.acciones.get(&nombre) {
                Some(f) => f(p),
                None => Respuesta::estado(500, ""),
            },
            // RUT-013: el 405 lleva Allow.
            Resolucion::VerboNoPermitido(verbos) => {
                let mut r = Respuesta::estado(405, "");
                r.extra.push(("Allow".into(), verbos.join(", ")));
                r
            }
            Resolucion::NoHay => Respuesta::estado(404, ""),
        }
    }
}

fn escribir(
    flujo: &mut TcpStream,
    r: Respuesta,
    solo_cabeceras: bool,
    cerrar: bool,
) -> std::io::Result<()> {
    let mut salida = format!(
        "HTTP/1.1 {} {}\r\nContent-Length: {}\r\nContent-Type: {}\r\n",
        r.estado,
        razon(r.estado),
        r.cuerpo.len(),
        r.tipo
    );
    for (n, v) in &r.extra {
        salida.push_str(&format!("{n}: {v}\r\n"));
    }
    salida.push_str(if cerrar { "Connection: close\r\n\r\n" } else { "\r\n" });
    flujo.write_all(salida.as_bytes())?;
    // HEAD manda las mismas cabeceras que GET y ningún octeto de cuerpo.
    if !solo_cabeceras {
        flujo.write_all(&r.cuerpo)?;
    }
    flujo.flush()
}

fn razon(estado: u16) -> &'static str {
    match estado {
        200 => "OK",
        204 => "No Content",
        400 => "Bad Request",
        404 => "Not Found",
        405 => "Method Not Allowed",
        431 => "Request Header Fields Too Large",
        500 => "Internal Server Error",
        501 => "Not Implemented",
        505 => "HTTP Version Not Supported",
        _ => "",
    }
}
