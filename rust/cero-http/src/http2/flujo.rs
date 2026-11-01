use std::collections::HashMap;

use super::hpack::{Codificador, Decodificador};
use super::trama::*;

#[derive(Debug, PartialEq)]
pub struct Cortado {
    pub flujo: u32,
    pub codigo: Error,
    pub porque: &'static str,
}

#[derive(Debug, PartialEq)]
pub enum Accion {
    Escribir(Trama),
    Peticion { flujo: u32, cabeceras: Vec<(String, String)>, fin: bool },
    Cuerpo { flujo: u32, datos: Vec<u8>, fin: bool },
    Anulado(u32),
    Credito { flujo: u32, cuanto: i64 },
    Reajustar(i64),
}

#[derive(Debug, PartialEq, Clone, Copy)]
enum Estado {
    Abierto,
    MitadCerradoRemoto,
}

struct Flujo {
    estado: Estado,
    ventana: i64,
    cabeceras_vistas: bool,
    malformado: Option<&'static str>,
    declarado: Option<u64>,
    recibido: u64,
}

#[derive(Debug, Clone, Copy)]
pub struct Topes {
    pub bloque_cabeceras: usize,
    pub anulados: u32,
    pub control_sin_flujo: u32,
}

impl Default for Topes {
    fn default() -> Topes {
        Topes { bloque_cabeceras: 64 * 1024, anulados: 200, control_sin_flujo: 1_000 }
    }
}

pub struct Sesion {
    pub ajustes: Ajustes,
    pub par: Ajustes,
    pub topes: Topes,
    decodificador: Decodificador,
    flujos: HashMap<u32, Flujo>,
    ultimo: u32,
    ventana: i64,
    pendiente: Option<(u32, Vec<u8>, bool)>,
    anulados: u32,
    control_sin_flujo: u32,
}

fn conexion(codigo: Error, porque: &'static str) -> FalloConexion {
    FalloConexion { codigo, porque }
}

impl Sesion {
    pub fn nueva(ajustes: Ajustes) -> Sesion {
        let ventana = ajustes.ventana_inicial as i64;
        Sesion {
            decodificador: Decodificador::nuevo(4096, ajustes.max_cabeceras as usize),
            flujos: HashMap::new(),
            ultimo: 0,
            ventana,
            pendiente: None,
            anulados: 0,
            control_sin_flujo: 0,
            ajustes,
            par: Ajustes::del_rfc(),
            topes: Topes::default(),
        }
    }

    pub fn es_preambulo(octetos: &[u8]) -> bool {
        octetos.len() >= PREAMBULO.len() && &octetos[..PREAMBULO.len()] == PREAMBULO
    }

    pub fn ultimo(&self) -> u32 {
        self.ultimo
    }

    pub fn activos(&self) -> usize {
        self.flujos.len()
    }

    pub fn ventana_de(&self, flujo: u32) -> Option<i64> {
        self.flujos.get(&flujo).map(|f| f.ventana)
    }

    pub fn recibir(&mut self, t: Trama) -> Result<(Vec<Accion>, Option<Cortado>), FalloConexion> {
        t.comprobar()?;

        if let Some((flujo, _, _)) = &self.pendiente {
            let esperado = *flujo;
            if t.tipo != CONTINUATION || t.flujo != esperado {
                return Err(conexion(Error::Protocolo, "trama por medio de un bloque de cabeceras"));
            }
        }

        match t.tipo {
            SETTINGS => self.settings(t),
            PING => self.ping(t),
            WINDOW_UPDATE => self.window_update(t),
            RST_STREAM => self.rst(t),
            HEADERS => self.headers(t),
            CONTINUATION => self.continuation(t),
            DATA => self.data(t),
            PRIORITY => self.priority(t),
            GOAWAY => Ok((Vec::new(), None)),
            _ => Ok((Vec::new(), None)),
        }
    }

    fn control(&mut self) -> Result<(), FalloConexion> {
        self.control_sin_flujo += 1;
        if self.control_sin_flujo > self.topes.control_sin_flujo {
            return Err(conexion(Error::Calma, "demasiadas tramas de control sin abrir un flujo"));
        }
        Ok(())
    }

    fn settings(&mut self, t: Trama) -> Result<(Vec<Accion>, Option<Cortado>), FalloConexion> {
        if t.banderas & RECONOCE != 0 {
            return Ok((Vec::new(), None));
        }
        self.control()?;
        let delta = self.par.aplicar(&t.carga)?;
        let mut acciones = vec![Accion::Escribir(Trama::settings_ack())];
        if delta != 0 {
            acciones.push(Accion::Reajustar(delta));
        }
        Ok((acciones, None))
    }

    fn ping(&mut self, t: Trama) -> Result<(Vec<Accion>, Option<Cortado>), FalloConexion> {
        if t.banderas & RECONOCE != 0 {
            return Ok((Vec::new(), None));
        }
        self.control()?;
        Ok((vec![Accion::Escribir(Trama::ping_ack(&t.carga))], None))
    }

    fn window_update(&mut self, t: Trama) -> Result<(Vec<Accion>, Option<Cortado>), FalloConexion> {
        let cuanto = u32::from_be_bytes([t.carga[0] & 0x7f, t.carga[1], t.carga[2], t.carga[3]]) as i64;
        if t.flujo == 0 {
            if self.flujos.is_empty() {
                self.control()?;
            }
            return Ok((vec![Accion::Credito { flujo: 0, cuanto }], None));
        }
        if t.flujo > self.ultimo {
            return Err(conexion(Error::Protocolo, "WINDOW_UPDATE sobre un flujo ocioso"));
        }
        if cuanto == 0 {
            return Ok((
                vec![Accion::Escribir(Trama::rst(t.flujo, Error::Protocolo))],
                Some(Cortado { flujo: t.flujo, codigo: Error::Protocolo, porque: "incremento cero" }),
            ));
        }
        Ok((vec![Accion::Credito { flujo: t.flujo, cuanto }], None))
    }

    fn priority(&mut self, t: Trama) -> Result<(Vec<Accion>, Option<Cortado>), FalloConexion> {
        if t.flujo == 0 {
            return Err(conexion(Error::Protocolo, "PRIORITY sobre la conexión"));
        }
        self.control()?;
        if depende_de_si_mismo(&t.carga, t.flujo) {
            return Ok((
                vec![Accion::Escribir(Trama::rst(t.flujo, Error::Protocolo))],
                Some(Cortado { flujo: t.flujo, codigo: Error::Protocolo, porque: "depende de sí mismo" }),
            ));
        }
        Ok((Vec::new(), None))
    }

    fn rst(&mut self, t: Trama) -> Result<(Vec<Accion>, Option<Cortado>), FalloConexion> {
        if t.flujo > self.ultimo {
            return Err(conexion(Error::Protocolo, "RST_STREAM de un flujo que nunca se abrió"));
        }
        self.flujos.remove(&t.flujo);
        self.anulados += 1;
        if self.anulados > self.topes.anulados {
            return Err(conexion(Error::Calma, "demasiados flujos abiertos y anulados"));
        }
        Ok((vec![Accion::Anulado(t.flujo)], None))
    }

    fn headers(&mut self, t: Trama) -> Result<(Vec<Accion>, Option<Cortado>), FalloConexion> {
        let trailers = match self.flujos.get(&t.flujo) {
            Some(f) if f.estado == Estado::MitadCerradoRemoto => {
                let porque = "cabeceras después del fin de flujo";
                return Ok((
                    vec![Accion::Escribir(Trama::rst(t.flujo, Error::FlujoCerrado))],
                    Some(Cortado { flujo: t.flujo, codigo: Error::FlujoCerrado, porque }),
                ));
            }
            Some(f) if f.cabeceras_vistas => {
                if !t.fin_flujo() {
                    return Err(conexion(Error::Protocolo, "segundo bloque de cabeceras sin fin de flujo"));
                }
                true
            }
            Some(_) => false,
            None => {
                if t.flujo <= self.ultimo {
                    return Err(conexion(Error::Protocolo, "identificador de flujo que no avanza"));
                }
                if self.activos() >= self.ajustes.max_flujos as usize {
                    return Ok((
                        vec![Accion::Escribir(Trama::rst(t.flujo, Error::Rechazado))],
                        Some(Cortado { flujo: t.flujo, codigo: Error::Rechazado, porque: "tope de flujos" }),
                    ));
                }
                self.ultimo = t.flujo;
                self.control_sin_flujo = 0;
                self.flujos.insert(
                    t.flujo,
                    Flujo {
                        estado: Estado::Abierto,
                        ventana: self.ajustes.ventana_inicial as i64,
                        cabeceras_vistas: false,
                        malformado: prioridad_de(&t)
                            .filter(|p| depende_de_si_mismo(p, t.flujo))
                            .map(|_| "el flujo depende de sí mismo"),
                        declarado: None,
                        recibido: 0,
                    },
                );
                false
            }
        };

        let bloque = recortar(&t)?;
        if t.fin_cabeceras() {
            self.terminar_bloque(t.flujo, bloque, t.fin_flujo(), trailers)
        } else {
            self.pendiente = Some((t.flujo, bloque, t.fin_flujo()));
            Ok((Vec::new(), None))
        }
    }

    fn continuation(&mut self, t: Trama) -> Result<(Vec<Accion>, Option<Cortado>), FalloConexion> {
        let (flujo, mut bloque, fin) = match self.pendiente.take() {
            Some(x) => x,
            None => return Err(conexion(Error::Protocolo, "CONTINUATION sin HEADERS delante")),
        };
        bloque.extend_from_slice(&t.carga);
        if bloque.len() > self.topes.bloque_cabeceras {
            return Err(conexion(Error::Calma, "bloque de cabeceras sin fin"));
        }
        let trailers = self.flujos.get(&flujo).map(|f| f.cabeceras_vistas).unwrap_or(false);
        if t.fin_cabeceras() {
            self.terminar_bloque(flujo, bloque, fin, trailers)
        } else {
            self.pendiente = Some((flujo, bloque, fin));
            Ok((Vec::new(), None))
        }
    }

    fn terminar_bloque(
        &mut self,
        flujo: u32,
        bloque: Vec<u8>,
        fin: bool,
        trailers: bool,
    ) -> Result<(Vec<Accion>, Option<Cortado>), FalloConexion> {
        let cabeceras = self.decodificador.decodificar(&bloque)?;

        let mut malformado = None;
        if let Some(f) = self.flujos.get_mut(&flujo) {
            f.cabeceras_vistas = true;
            if fin {
                f.estado = Estado::MitadCerradoRemoto;
            }
            malformado = f.malformado;
        }
        if let Some(porque) = malformado {
            return Ok((self.cortar(flujo, porque), Some(Cortado { flujo, codigo: Error::Protocolo, porque })));
        }
        if trailers {
            return Ok((vec![Accion::Cuerpo { flujo, datos: Vec::new(), fin: true }], None));
        }

        if let Err(porque) = validar(&cabeceras) {
            return Ok((self.cortar(flujo, porque), Some(Cortado { flujo, codigo: Error::Protocolo, porque })));
        }

        let declarado = match declarado(&cabeceras) {
            Err(porque) => return Ok((self.cortar(flujo, porque), Some(Cortado { flujo, codigo: Error::Protocolo, porque }))),
            Ok(d) => d,
        };
        if fin && declarado.unwrap_or(0) != 0 {
            let porque = "content-length sin cuerpo que lo respalde";
            return Ok((self.cortar(flujo, porque), Some(Cortado { flujo, codigo: Error::Protocolo, porque })));
        }
        if let Some(f) = self.flujos.get_mut(&flujo) {
            f.declarado = declarado;
        }
        Ok((vec![Accion::Peticion { flujo, cabeceras, fin }], None))
    }

    fn cortar(&mut self, flujo: u32, _porque: &'static str) -> Vec<Accion> {
        self.flujos.remove(&flujo);
        vec![Accion::Escribir(Trama::rst(flujo, Error::Protocolo))]
    }

    fn data(&mut self, t: Trama) -> Result<(Vec<Accion>, Option<Cortado>), FalloConexion> {
        let tamano = t.carga.len() as i64;
        self.ventana -= tamano;
        if self.ventana < 0 {
            return Err(conexion(Error::ControlDeFlujo, "el cliente se pasó de la ventana de la conexión"));
        }

        let datos = recortar(&t)?;
        self.ventana += tamano;
        let credito = || (tamano > 0).then(|| Accion::Escribir(Trama::ventana(0, tamano as u32)));
        let con_credito = |acciones: Vec<Accion>| credito().into_iter().chain(acciones).collect::<Vec<_>>();
        let estado = match self.flujos.get_mut(&t.flujo) {
            Some(f) => {
                f.ventana -= tamano;
                if f.ventana < 0 {
                    return Err(conexion(Error::ControlDeFlujo, "el cliente se pasó de la ventana del flujo"));
                }
                if f.estado == Estado::MitadCerradoRemoto {
                    return Ok((
                        con_credito(vec![Accion::Escribir(Trama::rst(t.flujo, Error::FlujoCerrado))]),
                        Some(Cortado { flujo: t.flujo, codigo: Error::FlujoCerrado, porque: "DATA tras fin de flujo" }),
                    ));
                }
                if t.fin_flujo() {
                    f.estado = Estado::MitadCerradoRemoto;
                }
                f.recibido += tamano as u64;
                let miente = match f.declarado {
                    Some(d) => f.recibido > d || (t.fin_flujo() && f.recibido != d),
                    None => false,
                };
                if miente {
                    let porque = "el cuerpo no mide lo que dijo content-length";
                    let cortado = Some(Cortado { flujo: t.flujo, codigo: Error::Protocolo, porque });
                    return Ok((con_credito(self.cortar(t.flujo, porque)), cortado));
                }
                Some(f.estado)
            }
            None => None,
        };
        if estado.is_none() {
            if t.flujo > self.ultimo {
                return Err(conexion(Error::Protocolo, "DATA sobre un flujo que nunca se abrió"));
            }
            return Ok((
                con_credito(vec![Accion::Escribir(Trama::rst(t.flujo, Error::FlujoCerrado))]),
                Some(Cortado { flujo: t.flujo, codigo: Error::FlujoCerrado, porque: "DATA sobre un flujo cerrado" }),
            ));
        }

        let mut acciones = vec![Accion::Cuerpo { flujo: t.flujo, datos, fin: t.fin_flujo() }];
        acciones.extend(credito());
        if tamano > 0 && !t.fin_flujo() {
            if let Some(f) = self.flujos.get_mut(&t.flujo) {
                f.ventana += tamano;
            }
            acciones.push(Accion::Escribir(Trama::ventana(t.flujo, tamano as u32)));
        }
        Ok((acciones, None))
    }

    pub fn desechar(&mut self, flujo: u32) {
        self.flujos.remove(&flujo);
    }

    pub fn respondido(&mut self, flujo: u32) {
        if let Some(f) = self.flujos.get(&flujo) {
            if f.estado == Estado::MitadCerradoRemoto {
                self.flujos.remove(&flujo);
            }
        }
    }
}

fn prioridad_de(t: &Trama) -> Option<&[u8]> {
    if t.banderas & PRIORIDAD == 0 {
        return None;
    }
    let desde = if t.banderas & RELLENO != 0 { 1 } else { 0 };
    t.carga.get(desde..desde + 4)
}

fn declarado(cabeceras: &[(String, String)]) -> Result<Option<u64>, &'static str> {
    match cabeceras.iter().find(|(n, _)| n == "content-length") {
        None => Ok(None),
        Some((_, v)) => v.parse().map(Some).map_err(|_| "content-length que no es un número"),
    }
}

fn depende_de_si_mismo(carga: &[u8], flujo: u32) -> bool {
    carga.len() >= 4 && u32::from_be_bytes([carga[0] & 0x7f, carga[1], carga[2], carga[3]]) == flujo
}

fn recortar(t: &Trama) -> Result<Vec<u8>, FalloConexion> {
    let mut desde = 0usize;
    let mut hasta = t.carga.len();
    if t.tipo == HEADERS && t.banderas & PRIORIDAD != 0 {
        if hasta < 5 {
            return Err(conexion(Error::TamanoDeTrama, "HEADERS con prioridad y sin los cinco octetos"));
        }
        desde += 5;
    }
    if t.banderas & RELLENO != 0 {
        if t.carga.is_empty() {
            return Err(conexion(Error::Protocolo, "relleno declarado y carga vacía"));
        }
        let relleno = t.carga[0] as usize;
        let (d, h) = if t.tipo == HEADERS && t.banderas & PRIORIDAD != 0 {
            (desde + 1, hasta)
        } else {
            (1, hasta)
        };
        desde = d;
        hasta = h;
        if relleno + desde > hasta {
            return Err(conexion(Error::Protocolo, "relleno mayor que la carga"));
        }
        hasta -= relleno;
    }
    Ok(t.carga[desde..hasta].to_vec())
}

const PSEUDO: [&str; 5] = [":method", ":scheme", ":path", ":authority", ":status"];

fn validar(cabeceras: &[(String, String)]) -> Result<(), &'static str> {
    let mut metodo = false;
    let mut esquema = false;
    let mut camino = false;
    let mut vistos: Vec<&str> = Vec::new();
    let mut ya_hubo_normal = false;

    for (nombre, valor) in cabeceras {
        if nombre.chars().any(|c| c.is_ascii_uppercase()) {
            return Err("nombre de campo con mayúsculas");
        }
        if nombre.is_empty() {
            return Err("nombre de campo vacío");
        }
        if nombre.starts_with(':') {
            if ya_hubo_normal {
                return Err("pseudo-campo después de un campo normal");
            }
            if !PSEUDO.contains(&nombre.as_str()) {
                return Err("pseudo-campo desconocido");
            }
            if nombre == ":status" {
                return Err(":status en una petición");
            }
            if vistos.contains(&nombre.as_str()) {
                return Err("pseudo-campo repetido");
            }
            vistos.push(nombre.as_str());
            match nombre.as_str() {
                ":method" => metodo = true,
                ":scheme" => esquema = true,
                ":path" => {
                    if valor.is_empty() {
                        return Err(":path vacío");
                    }
                    camino = true;
                }
                _ => {}
            }
        } else {
            ya_hubo_normal = true;
            if matches!(
                nombre.as_str(),
                "connection" | "keep-alive" | "proxy-connection" | "transfer-encoding" | "upgrade"
            ) {
                return Err("cabecera específica de conexión");
            }
            if nombre == "te" && valor.trim() != "trailers" {
                return Err("TE con un valor distinto de trailers");
            }
        }
    }
    if !metodo || !esquema || !camino {
        return Err("falta :method, :scheme o :path");
    }
    Ok(())
}

pub fn cabeceras_de_respuesta(estado: u16, cabeceras: &[(String, String)]) -> Vec<u8> {
    let mut salida = vec![(":status".to_string(), estado.to_string())];
    for (nombre, valor) in cabeceras {
        let minusculas = nombre.to_ascii_lowercase();
        if matches!(
            minusculas.as_str(),
            "connection" | "keep-alive" | "proxy-connection" | "transfer-encoding" | "upgrade"
        ) {
            continue;
        }
        salida.push((minusculas, valor.clone()));
    }
    Codificador::codificar(&salida)
}
