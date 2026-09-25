//! El framework: el bucle de aceptación y el pipeline que envuelve cada acción.
//!
//! Un hilo del sistema por conexión. Java usa un hilo virtual, que está en la plataforma; `std`
//! de Rust no tiene equivalente y cualquier runtime async es una dependencia, que es justo lo que
//! este proyecto no admite. El contrato no habla de hilos, así que se cumple igual — pero el
//! modelo de concurrencia **no** es el mismo, y eso está contado en LEEME.md.

use crate::contexto::{Contexto, Respuesta};
use crate::observabilidad::{self, Log, Metricas, Nivel, Salud};
use crate::peticion::{self, Peticion, Rechazo};
use crate::ruta::{Resolucion, Router};
use crate::seguridad::{self, Cabeceras, Cors, Decision, Limitador};
use crate::sesion::{self, Almacen};
use std::collections::HashMap;
use std::io::Write;
use std::net::{TcpListener, TcpStream};
use std::sync::Arc;
use std::time::{Duration, Instant};

type Accion = Box<dyn Fn(&Contexto) -> Respuesta + Send + Sync>;

pub struct Servidor {
    router: Router,
    acciones: HashMap<String, Accion>,
    sesiones: Almacen,
    cabeceras: Cabeceras,
    cors: Option<Cors>,
    limitador: Option<Limitador>,
    metricas: Metricas,
    log: Log,
    salud: Option<Salud>,
    /// Exenciones de CSRF. SEG-019: casan por segmento completo.
    csrf_exento: Vec<String>,
    csrf_activo: bool,
    /// SEG-005 y SEG-009 dependen de si la conexión es segura. Sin TLS propio todavía, lo dice
    /// quien monta el servidor, y mentir aquí solo se perjudica a sí mismo.
    seguro: bool,
}

impl Servidor {
    pub fn nuevo(router: Router) -> Servidor {
        Servidor {
            router,
            acciones: HashMap::new(),
            sesiones: Almacen::nuevo(Duration::from_secs(30 * 60), Some(Duration::from_secs(8 * 3600))),
            cabeceras: Cabeceras::default(),
            cors: None,
            limitador: None,
            metricas: Metricas::nuevas(),
            log: Log::nuevo("cero", Nivel::Info),
            salud: None,
            csrf_exento: Vec::new(),
            csrf_activo: false,
            seguro: false,
        }
    }

    pub fn accion(
        mut self,
        nombre: &str,
        f: impl Fn(&Contexto) -> Respuesta + Send + Sync + 'static,
    ) -> Servidor {
        self.acciones.insert(nombre.into(), Box::new(f));
        self
    }

    pub fn cors(mut self, c: Cors) -> Servidor {
        self.cors = Some(c);
        self
    }

    pub fn limite(mut self, cupo: u32, ventana: Duration) -> Servidor {
        self.limitador = Some(Limitador::nuevo(cupo, ventana));
        self
    }

    pub fn csrf(mut self, exenciones: &[&str]) -> Servidor {
        self.csrf_activo = true;
        self.csrf_exento = exenciones.iter().map(|e| e.to_string()).collect();
        self
    }

    pub fn salud(mut self, s: Salud) -> Servidor {
        self.salud = Some(s);
        self
    }

    pub fn cabeceras(mut self, c: Cabeceras) -> Servidor {
        self.cabeceras = c;
        self
    }

    pub fn tras_tls(mut self) -> Servidor {
        self.seguro = true;
        self
    }

    pub fn metricas(&self) -> &Metricas {
        &self.metricas
    }

    pub fn log(&self) -> &Log {
        &self.log
    }

    pub fn escuchar(self, puerto: u16) -> std::io::Result<()> {
        let oyente = TcpListener::bind(("0.0.0.0", puerto))?;
        let yo = Arc::new(self);
        yo.log.escribir(Nivel::Info, "cero · escuchando en :{}", &[&puerto.to_string()]);
        for conexion in oyente.incoming() {
            let Ok(flujo) = conexion else { continue };
            let yo = Arc::clone(&yo);
            std::thread::spawn(move || yo.atender(flujo));
        }
        Ok(())
    }

    fn atender(&self, mut flujo: TcpStream) {
        let cliente = flujo
            .peer_addr()
            .map(|a| a.ip().to_string())
            .unwrap_or_else(|_| "desconocido".into());
        loop {
            match peticion::leer(&flujo) {
                Ok(p) => {
                    let cerrar = p.version == "HTTP/1.0"
                        || p.cabecera("connection").is_some_and(|c| c.eq_ignore_ascii_case("close"));
                    let solo_cabeceras = p.metodo == "HEAD";
                    let r = self.pipeline(&p, &cliente);
                    if escribir(&mut flujo, r, solo_cabeceras, cerrar).is_err() || cerrar {
                        return;
                    }
                }
                Err(motivo) => {
                    let _ = escribir(&mut flujo, Respuesta::estado(motivo.estado(), ""), false, true);
                    return;
                }
            }
        }
    }

    /// El pipeline. RUT-028: envuelve la acción en orden; RUT-029: corre **también** cuando no
    /// hay ruta, porque un 404 sin cabeceras de seguridad deja sin proteger justo las respuestas
    /// que más se provocan desde fuera.
    fn pipeline(&self, p: &Peticion, cliente: &str) -> Respuesta {
        let empezo = Instant::now();
        let camino = p.camino().to_string();

        // 1 · salud, antes que nada: un proceso que no puede atender tiene que poder decirlo.
        if let Some(s) = &self.salud {
            let informe = match camino.as_str() {
                "/cero/vivo" => Some(s.vivo()),
                "/cero/listo" => Some(s.listo()),
                _ => None,
            };
            if let Some(i) = informe {
                return self.rematar(Respuesta { estado: i.estado, ..Respuesta::json_crudo(&i.cuerpo) },
                                    p, &camino, empezo, None);
            }
        }

        // 2 · límite de peticiones.
        if let Some(l) = &self.limitador {
            let v = l.pedir(cliente);
            let cabeceras = seguridad::cabeceras_limite(&v);
            if !v.permitida {
                let mut r = Respuesta::estado(429, "demasiadas peticiones");
                r.extra.extend(cabeceras);
                return self.rematar(r, p, &camino, empezo, None);
            }
        }

        // 3 · CORS, que puede cortar en seco un preflight ajeno.
        let mut de_cors = Vec::new();
        if let Some(c) = &self.cors {
            match c.decidir(&p.metodo, p.cabecera("origin")) {
                Decision::Corta(estado, h) => {
                    let mut r = Respuesta::estado(estado, "");
                    r.extra.extend(h);
                    return self.rematar(r, p, &camino, empezo, None);
                }
                Decision::Sigue(h) => de_cors = h,
            }
        }

        // 4 · sesión: se recupera, nunca se crea. SES-001.
        let id_cookie = sesion::id_de_cookie(p.cabecera("cookie")).map(str::to_string);
        let sesion = self.sesiones.recuperar(id_cookie.as_deref());

        // 5 · ruteo, **antes** que CSRF. Un verbo no admitido tiene que dar 405 y un camino
        // inexistente 404: si el CSRF responde 403 primero, la respuesta atribuye el fallo a la
        // causa equivocada, que es justo lo que RUT-009 prohíbe al exigir distinguir 404 de 405.
        // Se descubrió montando el framework entero: con los módulos sueltos no se veía.
        let resolucion = self.router.resolver(&p.metodo, &camino);
        if !matches!(resolucion, Resolucion::Encontrada(..)) && p.destino != "*" {
            let r = match &resolucion {
                Resolucion::VerboNoPermitido(verbos) => {
                    Respuesta::estado(405, "").cabecera("Allow", &verbos.join(", "))
                }
                _ => Respuesta::estado(404, "no encontrado"),
            };
            let mut r = r;
            r.extra.extend(de_cors);
            return self.rematar(r, p, &camino, empezo, sesion.clone());
        }

        // 6 · CSRF, ya sabiendo que la petición iba a alguna parte.
        if self.csrf_activo {
            let token_sesion = sesion.as_ref().and_then(|s| {
                s.lock().ok().and_then(|g| g.leer("csrf").ok().flatten().cloned())
            });
            let token_peticion = p.cabecera("x-csrf-token").map(str::to_string);
            if !seguridad::csrf_valido(&p.metodo, &camino, &self.csrf_exento,
                                       token_sesion.as_deref(), token_peticion.as_deref()) {
                return self.rematar(Respuesta::estado(403, "token CSRF ausente o inválido"),
                                    p, &camino, empezo, sesion);
            }
        }

        // 7 · la acción. `despachar` devuelve también la sesión que quedó en juego, porque la
        // acción pudo abrir una y esa es la que tiene cookie pendiente.
        let (mut r, sesion_final) = self.despachar(p, resolucion, sesion);
        r.extra.extend(de_cors);
        self.rematar(r, p, &camino, empezo, sesion_final)
    }

    fn despachar(
        &self,
        p: &Peticion,
        resolucion: Resolucion,
        sesion: Option<Arc<std::sync::Mutex<crate::Sesion>>>,
    ) -> (Respuesta, Option<Arc<std::sync::Mutex<crate::Sesion>>>) {
        if p.destino == "*" {
            // El vector de `spec/` fija 200 para `OPTIONS *`, no 204. Se cumple el contrato —es
            // lo que manda mientras haya discrepancia—, pero queda anotado en el LEEME que
            // conviene comprobar si el RFC lo exige o solo lo permite: un contrato que fija una
            // elección que la norma deja abierta está especificando de más.
            let r = if p.metodo == "OPTIONS" {
                Respuesta::estado(200, "")
            } else {
                Respuesta::estado(400, "")
            };
            return (r, sesion);
        }
        match resolucion {
            Resolucion::Encontrada(nombre, variables) => {
                let Some(f) = self.acciones.get(&nombre) else {
                    // RUT-024: no se filtra el detalle interno al cliente; va al log.
                    self.log.escribir(Nivel::Error, "ruta {} sin acción registrada", &[&nombre]);
                    return (Respuesta::estado(500, "error interno"), sesion);
                };
                let abrir = Box::new(|| self.sesiones.crear().ok());
                let ctx = Contexto::nuevo(p, variables, sesion, abrir);
                let r = f(&ctx);
                let final_ = ctx.sesion_final();
                (r, final_)
            }
            // RUT-013: el 405 lleva Allow.
            Resolucion::VerboNoPermitido(verbos) => (
                Respuesta::estado(405, "").cabecera("Allow", &verbos.join(", ")),
                sesion,
            ),
            Resolucion::NoHay => (Respuesta::estado(404, "no encontrado"), sesion),
        }
    }

    /// Lo que se aplica a **toda** respuesta salga por donde salga: cabeceras de seguridad, la
    /// cookie de sesión si hay una pendiente, métricas y log de acceso.
    ///
    /// Que esto sea un solo sitio no es estilo: `SES-010` nació de tener dos salidas y poner la
    /// cookie en una.
    fn rematar(
        &self,
        mut r: Respuesta,
        p: &Peticion,
        camino: &str,
        empezo: Instant,
        sesion: Option<Arc<std::sync::Mutex<crate::Sesion>>>,
    ) -> Respuesta {
        r.extra.extend(self.cabeceras.aplicar(self.seguro));

        // SES-008 y SES-011: se consulta una sola vez, aquí, y consultarla la consume.
        if let Some(s) = &sesion {
            if let Ok(mut g) = s.lock() {
                if let Some(id) = g.cookie_pendiente() {
                    r.extra.push(("Set-Cookie".into(), sesion::cabecera_cookie(&id, self.seguro)));
                }
            }
        }

        let patron = self.router.patron_de(&p.metodo, camino).unwrap_or_else(|| camino.to_string());
        self.metricas.anotar(&patron, r.estado, empezo.elapsed());
        self.log.escribir(
            Nivel::Info,
            "{}",
            &[&observabilidad::linea_acceso(&p.metodo, &p.destino, r.estado, None, empezo.elapsed())],
        );
        r
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
    if !solo_cabeceras {
        flujo.write_all(&r.cuerpo)?;
    }
    flujo.flush()
}

fn razon(estado: u16) -> &'static str {
    match estado {
        200 => "OK",
        204 => "No Content",
        302 => "Found",
        400 => "Bad Request",
        403 => "Forbidden",
        404 => "Not Found",
        405 => "Method Not Allowed",
        429 => "Too Many Requests",
        431 => "Request Header Fields Too Large",
        500 => "Internal Server Error",
        501 => "Not Implemented",
        503 => "Service Unavailable",
        505 => "HTTP Version Not Supported",
        _ => "",
    }
}
