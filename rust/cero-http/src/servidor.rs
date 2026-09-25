//! El framework: el bucle de aceptación y el pipeline que envuelve cada acción.
//!
//! Un hilo del sistema por conexión. Java usa un hilo virtual, que está en la plataforma; `std`
//! de Rust no tiene equivalente y cualquier runtime async es una dependencia, que es justo lo que
//! este proyecto no admite. El contrato no habla de hilos, así que se cumple igual — pero el
//! modelo de concurrencia **no** es el mismo, y eso está contado en LEEME.md.

use crate::contexto::{Contexto, Respuesta};
use crate::fallo::{EnRespuesta, Fallo};
use crate::observabilidad::{self, Log, Metricas, Nivel, Salud};
use crate::http2;
use crate::json::Json;
use crate::peticion::{self, Peticion};
use crate::registro::Registro;
use crate::ruta::{Captura, Resolucion, Router};
use crate::seguridad::{self, Cabeceras, Cors, Decision, Limitador};
use crate::sesion::{self, Almacen};
use std::collections::HashMap;
use std::io::{BufReader, Cursor, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::Arc;
use std::time::{Duration, Instant};

type Accion = Box<dyn Fn(&Contexto) -> Result<Respuesta, Fallo> + Send + Sync>;
type AlFallar = Box<dyn Fn(&Contexto, &Fallo) -> Respuesta + Send + Sync>;
type PorDefecto = Box<dyn Fn(&Peticion) -> Respuesta + Send + Sync>;
/// `RUT-028`: un middleware recibe el contexto y el resto de la cadena, así que puede mirar la
/// petición antes, tocar la respuesta después, o cortar sin llamar a `siguiente`.
pub type Siguiente<'s> = &'s dyn Fn(&Contexto) -> Respuesta;
type Medio = Box<dyn Fn(&Contexto, Siguiente) -> Respuesta + Send + Sync>;

pub struct Servidor {
    router: Router,
    acciones: HashMap<String, Accion>,
    /// `RUT-027`: qué hacer cuando una acción falla, si la aplicación quiere decidirlo.
    al_fallar: Option<AlFallar>,
    /// `RUT-030`: quién atiende lo que no enrutó nadie.
    por_defecto: Option<PorDefecto>,
    /// `OBS-023`: caminos que no se registran en el log de acceso.
    sin_registrar: Vec<String>,
    medios: Vec<Medio>,
    registro: Registro,
    sesiones: Arc<Almacen>,
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
            al_fallar: None,
            por_defecto: None,
            sin_registrar: Vec::new(),
            medios: Vec::new(),
            registro: Registro::nuevo(),
            sesiones: Arc::new(Almacen::nuevo(Duration::from_secs(30 * 60), Some(Duration::from_secs(8 * 3600)))),
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

    /// La acción puede devolver una `Respuesta` o un `Result`: `EnRespuesta` cubre las dos formas
    /// para que una acción que no falla no tenga que decir que no falla.
    pub fn accion<R: EnRespuesta>(
        mut self,
        nombre: &str,
        f: impl Fn(&Contexto) -> R + Send + Sync + 'static,
    ) -> Servidor {
        self.acciones.insert(nombre.into(), Box::new(move |c| f(c).en_respuesta()));
        self
    }

    /// `RUT-027`: el manejador fija el estado y construye el cuerpo. Corre **antes** que la regla
    /// por defecto, así que también decide qué se cuenta de un 500: es la aplicación la que sabe
    /// si su error interno es contable.
    pub fn al_fallar(
        mut self,
        f: impl Fn(&Contexto, &Fallo) -> Respuesta + Send + Sync + 'static,
    ) -> Servidor {
        self.al_fallar = Some(Box::new(f));
        self
    }

    /// `RUT-030`: atiende lo que no enrutó nadie —el caso típico es servir el index de una
    /// aplicación de una sola página—. No toca el 405: eso lo dice `RUT-031`.
    pub fn por_defecto(
        mut self,
        f: impl Fn(&Peticion) -> Respuesta + Send + Sync + 'static,
    ) -> Servidor {
        self.por_defecto = Some(Box::new(f));
        self
    }

    /// `RUT-028`: el middleware envuelve la acción en el orden en que se declaró — el primero
    /// declarado es el más exterior—. `RUT-029`: corre también cuando no hay ruta, porque un 404
    /// sin las cabeceras que pone el middleware deja sin proteger justo las respuestas que más se
    /// provocan desde fuera.
    pub fn usar(
        mut self,
        f: impl Fn(&Contexto, Siguiente) -> Respuesta + Send + Sync + 'static,
    ) -> Servidor {
        self.medios.push(Box::new(f));
        self
    }

    /// `OBS-023`: caminos que no salen en el log de acceso. El caso que lo pide es la sonda de
    /// salud, que en un orquestador entra cada pocos segundos y ahoga todo lo demás. Se declara
    /// por camino y no se adivina: un log que decide solo qué callarse esconde lo que no debe.
    pub fn sin_registrar(mut self, caminos: &[&str]) -> Servidor {
        self.sin_registrar = caminos.iter().map(|c| c.to_string()).collect();
        self
    }

    /// Las dependencias que las acciones van a poder pedir por `ctx.registro()`.
    pub fn con(mut self, montar: impl FnOnce(&mut Registro)) -> Servidor {
        montar(&mut self.registro);
        self
    }

    /// `SES-012`: dos servidores con el **mismo** almacén reconocen las mismas sesiones. Es lo que
    /// separa poder poner una segunda instancia detrás del balanceador de no poder.
    pub fn sesiones(mut self, a: Arc<Almacen>) -> Servidor {
        self.sesiones = a;
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
            std::thread::spawn(move || Servidor::atender(yo, flujo));
        }
        Ok(())
    }

    fn atender(yo: Arc<Servidor>, mut flujo: TcpStream) {
        let cliente = flujo
            .peer_addr()
            .map(|a| a.ip().to_string())
            .unwrap_or_else(|_| "desconocido".into());

        // La puerta de entrada por conocimiento previo. En un puerto compartido con HTTP/1.1 los
        // primeros octetos son lo único que distingue los dos protocolos, y lo leído se devuelve
        // para que no se pierda: equivocarse aquí deja a un cliente sin respuesta y sin saber por
        // qué.
        let prefijo = match http2::conexion::asomar_preambulo(&mut flujo) {
            Ok(Some(visto)) => visto,
            Ok(None) => {
                let suyo = Arc::clone(&yo);
                let de_quien = cliente.clone();
                let atender = Arc::new(move |p: Peticion| suyo.responder(&p, &de_quien));
                let _ = http2::conexion::servir(flujo, http2::Ajustes::default(), atender);
                return;
            }
            Err(_) => return,
        };

        let Ok(copia) = flujo.try_clone() else { return };
        let mut lector = BufReader::new(Cursor::new(prefijo).chain(copia));
        let yo = &*yo;
        loop {
            match peticion::leer(&mut lector) {
                Ok(p) => {
                    let cerrar = p.version == "HTTP/1.0"
                        || p.cabecera("connection").is_some_and(|c| c.eq_ignore_ascii_case("close"));
                    let solo_cabeceras = p.metodo == "HEAD";
                    let r = yo.responder(&p, &cliente);
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

    /// El pipeline entero **sin socket**: se le da una petición y devuelve la respuesta.
    ///
    /// Es público a propósito, y por el mismo motivo que `Sesion` de HTTP/2 no toca el socket: así
    /// se puede probar dando peticiones y mirando lo que sale, sin carreras y sin puertos. Es
    /// además lo que permite montar Cero dentro de otra cosa.
    pub fn responder(&self, p: &Peticion, cliente: &str) -> Respuesta {
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

        // 5 · se rutea y se arma el contexto, pero **no** se actúa todavía: entre una cosa y otra
        // va el middleware, que tiene que ver también las peticiones que no enrutaron (`RUT-029`).
        let resolucion = self.router.resolver(&p.metodo, &camino);
        let variables = match &resolucion {
            Resolucion::Encontrada(_, v) => v.clone(),
            _ => Captura::new(),
        };
        let abrir = Box::new(|| self.sesiones.crear().ok());
        let ctx = Contexto::nuevo(p, variables, sesion, abrir, &self.registro);

        let mut r = self.cadena(0, &ctx, &camino, &resolucion);
        r.extra.extend(de_cors);
        self.rematar(r, p, &camino, empezo, ctx.sesion_final())
    }

    /// El middleware, del más exterior al más interior, y al fondo lo que de verdad contesta.
    ///
    /// El índice y no una pila de clausuras: componer `Box<dyn Fn>` en Rust obliga a nombrar un
    /// tipo que se anida consigo mismo, y lo que se gana es ilegible. Con el índice, «el
    /// siguiente» es `i + 1` y se lee.
    fn cadena(&self, i: usize, ctx: &Contexto, camino: &str, r: &Resolucion) -> Respuesta {
        match self.medios.get(i) {
            Some(medio) => medio(ctx, &|c| self.cadena(i + 1, c, camino, r)),
            None => self.atender_ruta(ctx, camino, r),
        }
    }

    /// Ruteo, CSRF y acción. El orden importa: el ruteo va **antes** que el CSRF porque un verbo
    /// no admitido tiene que dar 405 y un camino inexistente 404, y si el CSRF responde 403
    /// primero la respuesta atribuye el fallo a la causa equivocada — que es lo que `RUT-009`
    /// prohíbe al exigir distinguir 404 de 405—. Se descubrió montando el framework entero: con
    /// los módulos sueltos no se veía.
    fn atender_ruta(&self, ctx: &Contexto, camino: &str, resolucion: &Resolucion) -> Respuesta {
        let p = ctx.peticion;
        if p.destino == "*" {
            // El vector de `spec/` fija 200 para `OPTIONS *`, no 204. Se cumple el contrato —es
            // lo que manda mientras haya discrepancia—, pero queda anotado en el LEEME que
            // conviene comprobar si el RFC lo exige o solo lo permite: un contrato que fija una
            // elección que la norma deja abierta está especificando de más.
            let estado = if p.metodo == "OPTIONS" { 200 } else { 400 };
            return Respuesta::estado(estado, "");
        }

        let nombre = match resolucion {
            Resolucion::Encontrada(nombre, _) => nombre,
            // `RUT-013` y `RUT-031`: un verbo no admitido sigue siendo 405 aunque haya manejador
            // por defecto. Devolverle la página del SPA a quien llama mal a la API le esconde su
            // propio error detrás de un 200.
            Resolucion::VerboNoPermitido(verbos) => {
                return Respuesta::estado(405, "").cabecera("Allow", &verbos.join(", "))
            }
            // `RUT-030` y `RUT-012`: el manejador por defecto si lo hay, y el 404 si no.
            Resolucion::NoHay => {
                return match &self.por_defecto {
                    Some(f) => f(p),
                    None => Respuesta::estado(404, "no encontrado"),
                }
            }
        };

        if let Some(r) = self.corta_el_csrf(ctx, camino) {
            return r;
        }
        self.actuar(ctx, nombre)
    }

    /// Ya sabiendo que la petición iba a alguna parte. `None` es que pasa.
    fn corta_el_csrf(&self, ctx: &Contexto, camino: &str) -> Option<Respuesta> {
        if !self.csrf_activo {
            return None;
        }
        let token_sesion = ctx.sesion().and_then(|s| {
            s.lock().ok().and_then(|g| g.leer(seguridad::CLAVE_CSRF).ok().flatten().cloned())
        });
        // Por cabecera, por campo del formulario o por la consulta. Solo la cabecera dejaba fuera
        // a un formulario HTML, que es justo donde el CSRF hace más falta.
        let token_peticion = ctx
            .peticion
            .cabecera("x-csrf-token")
            .map(str::to_string)
            .or_else(|| ctx.campo(seguridad::CAMPO_CSRF))
            .or_else(|| ctx.consulta(seguridad::CAMPO_CSRF));
        let valido = seguridad::csrf_valido(&ctx.peticion.metodo, camino, &self.csrf_exento,
                                            token_sesion.as_deref(), token_peticion.as_deref());
        (!valido).then(|| Respuesta::estado(403, "token CSRF ausente o inválido"))
    }

    /// La acción, y lo que se hace con ella si falla.
    fn actuar(&self, ctx: &Contexto, nombre: &str) -> Respuesta {
        // La ruta es el patrón, no el camino que llegó: nombra lo mismo para quien lo tenga que
        // informar y no devuelve al cliente lo que el cliente mandó.
        let ruta = self
            .router
            .patron_de(&ctx.peticion.metodo, ctx.peticion.camino())
            .unwrap_or_else(|| nombre.to_string());
        let Some(f) = self.acciones.get(nombre) else {
            let fallo = Fallo::interno(&format!("la ruta {nombre} no tiene acción registrada"));
            return self.del_fallo(ctx, &ruta, fallo);
        };
        match f(ctx) {
            Ok(r) => r,
            Err(fallo) => self.del_fallo(ctx, &ruta, fallo),
        }
    }

    /// Qué sale cuando una acción no pudo responder.
    ///
    /// El orden importa y es `RUT-027` primero: si la aplicación declaró un manejador, manda ella,
    /// incluso sobre un 500. Lo demás es la regla por defecto, y su única decisión difícil es la
    /// de `RUT-024` — un 5xx no cuenta su mensaje, porque ahí el mensaje habla de las tripas del
    /// servidor y quien lo provocó no tiene por qué verlas.
    fn del_fallo(&self, ctx: &Contexto, ruta: &str, fallo: Fallo) -> Respuesta {
        if let Some(manejador) = &self.al_fallar {
            return manejador(ctx, &fallo);
        }
        // `RUT-026`: el estado declarado se conserva, y con él su mensaje o su detalle.
        if fallo.contable() {
            return match &fallo.detalle {
                Some(d) => Respuesta { estado: fallo.estado, ..Respuesta::json_crudo(&d.escribir()) },
                None => Respuesta::estado(fallo.estado, &fallo.mensaje),
            };
        }
        self.log.escribir(Nivel::Error, "{} falló: {}", &[ruta, &fallo.mensaje]);
        // `RUT-025`: el cuerpo identifica la ruta, para que quien lo reciba pueda informarlo.
        let cuerpo = Json::objeto(vec![
            ("error", Json::Texto("error interno".into())),
            ("ruta", Json::Texto(ruta.into())),
        ]);
        Respuesta { estado: fallo.estado, ..Respuesta::json(cuerpo) }
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
        // `OBS-012`: el estado que se registra es el que **sale**. Anotarlo antes de que la acción
        // pueda fallar deja un log lleno de doscientos que el cliente recibió como quinientos, y
        // entonces el log dice justo lo contrario de lo que pasó.
        if !self.sin_registrar.iter().any(|c| c == camino) {
            self.log.escribir(
                Nivel::Info,
                "{}",
                &[&observabilidad::linea_acceso(&p.metodo, &p.destino, r.estado, None, empezo.elapsed())],
            );
        }
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
