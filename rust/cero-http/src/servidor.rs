use crate::contexto::{Contexto, Respuesta};
use crate::fallo::{EnRespuesta, Fallo};
use crate::observabilidad::{self, Log, Metricas, Nivel, Salud};
use crate::http2;
use crate::json::Json;
use crate::peticion::{self, Peticion};
use crate::registro::Registro;
use crate::ruta::{Captura, Resolucion, Router};
use crate::seguridad::{self, Cabeceras, Cors, Decision, Limitador};
use crate::sesion::{self, Sesiones};
use crate::socket::{mandar_todo, Limites, Plazos};
use std::collections::HashMap;
use std::io::{BufRead, BufReader, Cursor, ErrorKind, Read};
use std::net::{TcpListener, TcpStream};
use std::sync::Arc;
use std::time::{Duration, Instant};

type Accion = Box<dyn Fn(&Contexto) -> Result<Respuesta, Fallo> + Send + Sync>;
type AlFallar = Box<dyn Fn(&Contexto, &Fallo) -> Respuesta + Send + Sync>;
type PorDefecto = Box<dyn Fn(&Peticion) -> Respuesta + Send + Sync>;
pub type Siguiente<'s> = &'s dyn Fn(&Contexto) -> Respuesta;
type Medio = Box<dyn Fn(&Contexto, Siguiente) -> Respuesta + Send + Sync>;

pub struct Servidor {
    router: Router,
    acciones: HashMap<String, Accion>,
    al_fallar: Option<AlFallar>,
    por_defecto: Option<PorDefecto>,
    sin_registrar: Vec<String>,
    medios: Vec<Medio>,
    registro: Registro,
    sesiones: Arc<dyn Sesiones>,
    cabeceras: Cabeceras,
    cors: Option<Cors>,
    limitador: Option<Limitador>,
    metricas: Metricas,
    log: Log,
    salud: Option<Salud>,
    csrf_exento: Vec<String>,
    csrf_activo: bool,
    seguro: bool,
    plazos: Plazos,
    limites: Limites,
    direccion: String,
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
            sesiones: Arc::new(sesion::Almacen::nuevo(
                Duration::from_secs(30 * 60),
                Some(Duration::from_secs(8 * 3600)),
            )),
            cabeceras: Cabeceras::default(),
            cors: None,
            limitador: None,
            metricas: Metricas::nuevas(),
            log: Log::nuevo("cero", Nivel::Info),
            salud: None,
            csrf_exento: Vec::new(),
            csrf_activo: false,
            seguro: false,
            plazos: Plazos::default(),
            limites: Limites::default(),
            direccion: "0.0.0.0".to_string(),
        }
    }

    pub fn accion<R: EnRespuesta>(
        mut self,
        nombre: &str,
        f: impl Fn(&Contexto) -> R + Send + Sync + 'static,
    ) -> Servidor {
        self.acciones.insert(nombre.into(), Box::new(move |c| f(c).en_respuesta()));
        self
    }

    pub fn al_fallar(
        mut self,
        f: impl Fn(&Contexto, &Fallo) -> Respuesta + Send + Sync + 'static,
    ) -> Servidor {
        self.al_fallar = Some(Box::new(f));
        self
    }

    pub fn por_defecto(
        mut self,
        f: impl Fn(&Peticion) -> Respuesta + Send + Sync + 'static,
    ) -> Servidor {
        self.por_defecto = Some(Box::new(f));
        self
    }

    pub fn usar(
        mut self,
        f: impl Fn(&Contexto, Siguiente) -> Respuesta + Send + Sync + 'static,
    ) -> Servidor {
        self.medios.push(Box::new(f));
        self
    }

    pub fn sin_registrar(mut self, caminos: &[&str]) -> Servidor {
        self.sin_registrar = caminos.iter().map(|c| c.to_string()).collect();
        self
    }

    pub fn con(mut self, montar: impl FnOnce(&mut Registro)) -> Servidor {
        montar(&mut self.registro);
        self
    }

    pub fn sesiones(mut self, a: Arc<dyn Sesiones>) -> Servidor {
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

    pub fn plazos(mut self, p: Plazos) -> Servidor {
        self.plazos = p;
        self
    }

    pub fn limites(mut self, l: Limites) -> Servidor {
        self.limites = l;
        self
    }

    pub fn direccion(mut self, d: &str) -> Servidor {
        self.direccion = d.to_string();
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
        if !self.plazos.validos() || !self.limites.validos() {
            return Err(std::io::Error::new(
                ErrorKind::InvalidInput,
                "los plazos y los topes tienen que ser mayores que cero",
            ));
        }
        let oyente = TcpListener::bind((self.direccion.as_str(), puerto))?;
        let yo = Arc::new(self);
        yo.log.escribir(Nivel::Info, "cero · escuchando en :{}", &[&puerto.to_string()]);
        for conexion in oyente.incoming() {
            let Ok(flujo) = conexion else { continue };
            let yo = Arc::clone(&yo);
            let _ = std::thread::Builder::new().spawn(move || Servidor::atender(yo, flujo));
        }
        Ok(())
    }

    fn atender(yo: Arc<Servidor>, mut flujo: TcpStream) {
        let cliente = flujo
            .peer_addr()
            .map(|a| a.ip().to_string())
            .unwrap_or_else(|_| "desconocido".into());
        let (plazos, limites) = (yo.plazos, yo.limites);
        if flujo.set_read_timeout(Some(plazos.inactividad)).is_err() {
            return;
        }

        let prefijo = match http2::conexion::asomar_preambulo(&mut flujo) {
            Ok(Some(visto)) => visto,
            Ok(None) => {
                let suyo = Arc::clone(&yo);
                let de_quien = cliente.clone();
                let atender = Arc::new(move |p: Peticion| suyo.responder(&p, &de_quien));
                let _ = http2::conexion::servir(flujo, http2::Ajustes::default(), atender, plazos, limites);
                return;
            }
            Err(_) => return,
        };

        let Ok(copia) = flujo.try_clone() else { return };
        let mut lector = BufReader::new(Cursor::new(prefijo).chain(copia));
        let yo = &*yo;
        while lector.fill_buf().is_ok_and(|hay| !hay.is_empty()) {
            match peticion::leer(&mut lector, limites.cuerpo) {
                Ok(p) => {
                    let cerrar = p.version == "HTTP/1.0"
                        || p.cabecera("connection").is_some_and(|c| c.eq_ignore_ascii_case("close"));
                    let solo_cabeceras = p.metodo == "HEAD";
                    let r = yo.responder(&p, &cliente);
                    if escribir(&mut flujo, r, solo_cabeceras, cerrar, &plazos).is_err() || cerrar {
                        return;
                    }
                }
                Err(motivo) => {
                    let _ = escribir(&mut flujo, Respuesta::estado(motivo.estado(), ""), false, true, &plazos);
                    return;
                }
            }
        }
    }

    pub fn responder(&self, p: &Peticion, cliente: &str) -> Respuesta {
        let empezo = Instant::now();
        let camino = p.camino().to_string();

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

        if let Some(l) = &self.limitador {
            let v = l.pedir(cliente);
            let cabeceras = seguridad::cabeceras_limite(&v);
            if !v.permitida {
                let mut r = Respuesta::estado(429, "demasiadas peticiones");
                r.extra.extend(cabeceras);
                return self.rematar(r, p, &camino, empezo, None);
            }
        }

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

        let id_cookie = sesion::id_de_cookie(p.cabecera("cookie")).map(str::to_string);
        let sesion = self.sesiones.recuperar(id_cookie.as_deref());

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

    fn cadena(&self, i: usize, ctx: &Contexto, camino: &str, r: &Resolucion) -> Respuesta {
        match self.medios.get(i) {
            Some(medio) => medio(ctx, &|c| self.cadena(i + 1, c, camino, r)),
            None => self.atender_ruta(ctx, camino, r),
        }
    }

    fn atender_ruta(&self, ctx: &Contexto, camino: &str, resolucion: &Resolucion) -> Respuesta {
        let p = ctx.peticion;
        if p.destino == "*" {
            let estado = if p.metodo == "OPTIONS" { 200 } else { 400 };
            return Respuesta::estado(estado, "");
        }

        let nombre = match resolucion {
            Resolucion::Encontrada(nombre, _) => nombre,
            Resolucion::VerboNoPermitido(verbos) => {
                return Respuesta::estado(405, "").cabecera("Allow", &verbos.join(", "))
            }
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

    fn corta_el_csrf(&self, ctx: &Contexto, camino: &str) -> Option<Respuesta> {
        if !self.csrf_activo {
            return None;
        }
        let token_sesion = ctx.sesion().and_then(|s| {
            s.lock().ok().and_then(|g| g.leer(seguridad::CLAVE_CSRF).ok().flatten().cloned())
        });
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

    fn actuar(&self, ctx: &Contexto, nombre: &str) -> Respuesta {
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

    fn del_fallo(&self, ctx: &Contexto, ruta: &str, fallo: Fallo) -> Respuesta {
        if let Some(manejador) = &self.al_fallar {
            return manejador(ctx, &fallo);
        }
        if fallo.contable() {
            return match &fallo.detalle {
                Some(d) => Respuesta { estado: fallo.estado, ..Respuesta::json_crudo(&d.escribir()) },
                None => Respuesta::estado(fallo.estado, &fallo.mensaje),
            };
        }
        self.log.escribir(Nivel::Error, "{} falló: {}", &[ruta, &fallo.mensaje]);
        let cuerpo = Json::objeto(vec![
            ("error", Json::Texto("error interno".into())),
            ("ruta", Json::Texto(ruta.into())),
        ]);
        Respuesta { estado: fallo.estado, ..Respuesta::json(cuerpo) }
    }

    fn rematar(
        &self,
        mut r: Respuesta,
        p: &Peticion,
        camino: &str,
        empezo: Instant,
        sesion: Option<Arc<std::sync::Mutex<crate::Sesion>>>,
    ) -> Respuesta {
        r.extra.extend(self.cabeceras.aplicar(self.seguro));

        if let Some(s) = &sesion {
            if let Ok(mut g) = s.lock() {
                if let Some(id) = g.cookie_pendiente() {
                    r.extra.push(("Set-Cookie".into(), sesion::cabecera_cookie(&id, self.seguro)));
                }
            }
            self.sesiones.guardar(s);
        }

        let patron = self.router.patron_de(&p.metodo, camino).unwrap_or_else(|| camino.to_string());
        self.metricas.anotar(&patron, r.estado, empezo.elapsed());
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
    plazos: &Plazos,
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
    let mut octetos = salida.into_bytes();
    if !solo_cabeceras {
        octetos.extend_from_slice(&r.cuerpo);
    }
    mandar_todo(flujo, &octetos, plazos)
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
        413 => "Content Too Large",
        431 => "Request Header Fields Too Large",
        500 => "Internal Server Error",
        501 => "Not Implemented",
        503 => "Service Unavailable",
        505 => "HTTP Version Not Supported",
        _ => "",
    }
}
