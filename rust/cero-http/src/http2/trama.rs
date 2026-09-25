//! Tramas HTTP/2, según `spec/http2.md`.
//!
//! Casi todos los requisitos de este bloque son rechazos: qué **no** se admite y con qué código.
//! Esa asimetría no es casual — un servidor HTTP/2 permisivo es un servidor que desacuerda con
//! el proxy que tiene delante, y ahí empieza el contrabando.

use std::io::{Read, Write};

pub const PREAMBULO: &[u8] = b"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";

pub const DATA: u8 = 0x0;
pub const HEADERS: u8 = 0x1;
pub const PRIORITY: u8 = 0x2;
pub const RST_STREAM: u8 = 0x3;
pub const SETTINGS: u8 = 0x4;
pub const PUSH_PROMISE: u8 = 0x5;
pub const PING: u8 = 0x6;
pub const GOAWAY: u8 = 0x7;
pub const WINDOW_UPDATE: u8 = 0x8;
pub const CONTINUATION: u8 = 0x9;

pub const FIN_FLUJO: u8 = 0x1;
pub const FIN_CABECERAS: u8 = 0x4;
pub const RECONOCE: u8 = 0x1;
pub const RELLENO: u8 = 0x8;
pub const PRIORIDAD: u8 = 0x20;

/// Códigos de error del RFC 9113 §7.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Error {
    Ninguno = 0x0,
    Protocolo = 0x1,
    Interno = 0x2,
    ControlDeFlujo = 0x3,
    TamanoDeTrama = 0x6,
    Rechazado = 0x7,
    Anulado = 0x8,
    FlujoCerrado = 0x5,
    Compresion = 0x9,
    Calma = 0xb,
}

/// Un fallo que se lleva la conexión entera por delante.
#[derive(Debug)]
pub struct FalloConexion {
    pub codigo: Error,
    pub porque: &'static str,
}

/// Un fallo que solo anula un flujo. La distinción importa: tumbar la conexión por un flujo
/// malo deja sin servicio a los demás, que es justo lo que HTTP/2 vino a evitar.
#[derive(Debug)]
pub struct FalloFlujo {
    pub flujo: u32,
    pub codigo: Error,
}

pub type Resultado<T> = Result<T, FalloConexion>;

fn mal(codigo: Error, porque: &'static str) -> FalloConexion {
    FalloConexion { codigo, porque }
}

#[derive(Debug)]
pub struct Trama {
    pub tipo: u8,
    pub banderas: u8,
    pub flujo: u32,
    pub carga: Vec<u8>,
}

impl Trama {
    pub fn fin_flujo(&self) -> bool {
        self.banderas & FIN_FLUJO != 0
    }

    pub fn fin_cabeceras(&self) -> bool {
        self.banderas & FIN_CABECERAS != 0
    }

    /// H2-014: una trama mayor que lo negociado no se admite. Se comprueba **antes** de leer la
    /// carga: si se leyera primero, anunciar 16 MB bastaría para que el servidor los reservara.
    pub fn leer(origen: &mut impl Read, max: u32) -> Resultado<Trama> {
        let mut cabecera = [0u8; 9];
        origen
            .read_exact(&mut cabecera)
            .map_err(|_| mal(Error::Ninguno, "la conexión se cerró"))?;
        let largo = u32::from_be_bytes([0, cabecera[0], cabecera[1], cabecera[2]]);
        if largo > max {
            return Err(mal(Error::TamanoDeTrama, "trama mayor que el máximo negociado"));
        }
        let tipo = cabecera[3];
        let banderas = cabecera[4];
        // El bit más alto del identificador está reservado y se ignora, no se rechaza.
        let flujo = u32::from_be_bytes([cabecera[5] & 0x7f, cabecera[6], cabecera[7], cabecera[8]]);
        let mut carga = vec![0u8; largo as usize];
        origen
            .read_exact(&mut carga)
            .map_err(|_| mal(Error::Ninguno, "carga incompleta"))?;
        Ok(Trama { tipo, banderas, flujo, carga })
    }

    pub fn escribir(&self, destino: &mut impl Write) -> std::io::Result<()> {
        let n = self.carga.len();
        let cabecera = [
            (n >> 16) as u8, (n >> 8) as u8, n as u8,
            self.tipo, self.banderas,
            (self.flujo >> 24) as u8, (self.flujo >> 16) as u8,
            (self.flujo >> 8) as u8, self.flujo as u8,
        ];
        destino.write_all(&cabecera)?;
        destino.write_all(&self.carga)
    }

    /// Los rechazos que dependen solo de la trama, sin mirar el estado de la conexión.
    /// `H2-002` a `H2-013`.
    pub fn comprobar(&self) -> Resultado<()> {
        match self.tipo {
            // H2-004: DATA sobre el flujo 0.
            DATA if self.flujo == 0 => Err(mal(Error::Protocolo, "DATA sobre el flujo 0")),
            // H2-005 y H2-006: HEADERS sobre el flujo 0 o sobre un flujo par.
            HEADERS if self.flujo == 0 => Err(mal(Error::Protocolo, "HEADERS sobre el flujo 0")),
            HEADERS if self.flujo % 2 == 0 => {
                Err(mal(Error::Protocolo, "HEADERS sobre un flujo par: los pares son del servidor"))
            }
            // H2-002 y H2-003.
            SETTINGS if self.flujo != 0 => Err(mal(Error::Protocolo, "SETTINGS sobre un flujo")),
            SETTINGS if self.banderas & RECONOCE != 0 && !self.carga.is_empty() => {
                Err(mal(Error::TamanoDeTrama, "SETTINGS con ACK y carga"))
            }
            SETTINGS if self.carga.len() % 6 != 0 => {
                Err(mal(Error::TamanoDeTrama, "SETTINGS no múltiplo de seis"))
            }
            // H2-009: un cliente no promete nada.
            PUSH_PROMISE => Err(mal(Error::Protocolo, "PUSH_PROMISE de un cliente")),
            // H2-010 y H2-011.
            PING if self.flujo != 0 => Err(mal(Error::Protocolo, "PING sobre un flujo")),
            PING if self.carga.len() != 8 => Err(mal(Error::TamanoDeTrama, "PING que no mide ocho")),
            // H2-012 y H2-013.
            WINDOW_UPDATE if self.carga.len() != 4 => {
                Err(mal(Error::TamanoDeTrama, "WINDOW_UPDATE que no mide cuatro"))
            }
            WINDOW_UPDATE if self.flujo == 0 && self.incremento() == 0 => {
                Err(mal(Error::Protocolo, "incremento de ventana cero sobre la conexión"))
            }
            RST_STREAM if self.flujo == 0 => Err(mal(Error::Protocolo, "RST_STREAM sobre el flujo 0")),
            RST_STREAM if self.carga.len() != 4 => {
                Err(mal(Error::TamanoDeTrama, "RST_STREAM que no mide cuatro"))
            }
            PRIORITY if self.carga.len() != 5 => {
                Err(mal(Error::TamanoDeTrama, "PRIORITY que no mide cinco"))
            }
            GOAWAY if self.flujo != 0 => Err(mal(Error::Protocolo, "GOAWAY sobre un flujo")),
            _ => Ok(()),
        }
    }

    fn incremento(&self) -> u32 {
        if self.carga.len() < 4 {
            return 0;
        }
        u32::from_be_bytes([self.carga[0] & 0x7f, self.carga[1], self.carga[2], self.carga[3]])
    }

    pub fn ping_ack(carga: &[u8]) -> Trama {
        Trama { tipo: PING, banderas: RECONOCE, flujo: 0, carga: carga.to_vec() }
    }

    pub fn settings_ack() -> Trama {
        Trama { tipo: SETTINGS, banderas: RECONOCE, flujo: 0, carga: Vec::new() }
    }

    pub fn rst(flujo: u32, codigo: Error) -> Trama {
        Trama { tipo: RST_STREAM, banderas: 0, flujo, carga: (codigo as u32).to_be_bytes().to_vec() }
    }

    pub fn goaway(ultimo: u32, codigo: Error) -> Trama {
        let mut carga = Vec::with_capacity(8);
        carga.extend_from_slice(&ultimo.to_be_bytes());
        carga.extend_from_slice(&(codigo as u32).to_be_bytes());
        Trama { tipo: GOAWAY, banderas: 0, flujo: 0, carga }
    }

    pub fn ventana(flujo: u32, cuanto: u32) -> Trama {
        Trama { tipo: WINDOW_UPDATE, banderas: 0, flujo, carga: cuanto.to_be_bytes().to_vec() }
    }
}

/// Los ajustes que el RFC 9113 §6.5.2 define y este servidor usa.
#[derive(Debug, Clone, Copy)]
pub struct Ajustes {
    pub max_flujos: u32,
    pub ventana_inicial: u32,
    pub max_trama: u32,
    pub max_cabeceras: u32,
}

impl Default for Ajustes {
    fn default() -> Ajustes {
        Ajustes { max_flujos: 128, ventana_inicial: 65_535, max_trama: 16_384, max_cabeceras: 16_384 }
    }
}

impl Ajustes {
    /// H2-027: el servidor manda los suyos como primera trama.
    pub fn trama(&self) -> Trama {
        let mut c = Vec::with_capacity(24);
        for (id, v) in [
            (0x2u16, 0u32),                    // push deshabilitado
            (0x3, self.max_flujos),
            (0x4, self.ventana_inicial),
            (0x5, self.max_trama),
            (0x6, self.max_cabeceras),
        ] {
            c.extend_from_slice(&id.to_be_bytes());
            c.extend_from_slice(&v.to_be_bytes());
        }
        Trama { tipo: SETTINGS, banderas: 0, flujo: 0, carga: c }
    }

    /// Aplica los del cliente. Devuelve el delta de ventana inicial, que hay que sumar a los
    /// flujos ya abiertos (RFC 9113 §6.9.2).
    pub fn aplicar(&mut self, carga: &[u8]) -> Resultado<i64> {
        let mut delta = 0i64;
        for par in carga.chunks_exact(6) {
            let id = u16::from_be_bytes([par[0], par[1]]);
            let v = u32::from_be_bytes([par[2], par[3], par[4], par[5]]);
            match id {
                0x2 if v > 1 => return Err(mal(Error::Protocolo, "ENABLE_PUSH que no es 0 ni 1")),
                0x4 => {
                    if v > 0x7fff_ffff {
                        return Err(mal(Error::ControlDeFlujo, "ventana inicial imposible"));
                    }
                    delta = v as i64 - self.ventana_inicial as i64;
                    self.ventana_inicial = v;
                }
                0x5 => {
                    if !(16_384..=16_777_215).contains(&v) {
                        return Err(mal(Error::Protocolo, "MAX_FRAME_SIZE fuera de rango"));
                    }
                    self.max_trama = v;
                }
                0x3 => self.max_flujos = v,
                0x6 => self.max_cabeceras = v,
                // Un ajuste desconocido se ignora, no se rechaza: es lo que permite extender el
                // protocolo sin romper a quien no lo conoce.
                _ => {}
            }
        }
        Ok(delta)
    }
}
