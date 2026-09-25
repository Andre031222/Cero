//! Los flujos de HTTP/2: la máquina de estados, el control de flujo y los topes de inundación.
//!
//! Esta capa es la que convierte tramas en peticiones, y es donde el protocolo deja de parecerse a
//! HTTP/1.1. Tres cosas que no son evidentes y que deciden el diseño:
//!
//! 1. **Terminar de responder no cierra el flujo.** Cierra *nuestra* mitad; el cliente puede seguir
//!    mandando cuerpo hasta que ponga `END_STREAM`. Olvidar el flujo al responder es exactamente el
//!    fallo que la 0.8.0 arregló en la implementación de Java: la comprobación de `content-length`
//!    dejaba de existir para lo que llegara después, y eso es la puerta del contrabando.
//! 2. **Un flujo malo no es una conexión mala.** Confundirlos convierte una petición mal formada en
//!    una caída de todo lo que ese cliente tuviera en vuelo, que es justo lo que HTTP/2 vino a
//!    evitar. Por eso hay dos tipos de fallo y no uno.
//! 3. **Las inundaciones son tramas válidas.** Ningún control de sintaxis las caza: lo que las
//!    define es que el cliente pide trabajo sin coste propio. Sin topes explícitos, una sola
//!    conexión basta para una negación de servicio, y de ahí salen CVE-2023-44487 y CVE-2024-27316.

use std::collections::HashMap;

use super::hpack::{Codificador, Decodificador};
use super::trama::*;

/// Un fallo que solo se lleva un flujo. El resto de la conexión sigue.
#[derive(Debug, PartialEq)]
pub struct Cortado {
    pub flujo: u32,
    pub codigo: Error,
    pub porque: &'static str,
}

/// Lo que la sesión pide hacer después de digerir una trama.
#[derive(Debug, PartialEq)]
pub enum Accion {
    /// Escribir esta trama tal cual: ACK, ventana, RST o GOAWAY.
    Escribir(Trama),
    /// Una petición completa de cabeceras, lista para el ruteo.
    Peticion { flujo: u32, cabeceras: Vec<(String, String)>, fin: bool },
    /// Un trozo de cuerpo.
    Cuerpo { flujo: u32, datos: Vec<u8>, fin: bool },
    /// El cliente anuló un flujo: si había trabajo en marcha, se abandona.
    Anulado(u32),
    /// El cliente amplía lo que se le puede mandar. `flujo` 0 es la conexión entera.
    Credito { flujo: u32, cuanto: i64 },
    /// Una ventana inicial nueva: mueve la de salida de todos los flujos abiertos (§6.9.2).
    Reajustar(i64),
}

#[derive(Debug, PartialEq, Clone, Copy)]
enum Estado {
    /// Cabeceras recibidas, el cliente puede seguir mandando cuerpo.
    Abierto,
    /// El cliente puso `END_STREAM`: no llega nada más por su lado.
    MitadCerradoRemoto,
}

struct Flujo {
    estado: Estado,
    /// Lo que aún se le admite recibir. Baja con cada DATA y sube con WINDOW_UPDATE.
    ventana: i64,
    /// Si ya llegó un bloque de cabeceras completo. El segundo son trailers, y solo valen con
    /// `END_STREAM` (`H2-017`).
    cabeceras_vistas: bool,
    /// Un fallo que se ve en la trama pero no se puede contestar hasta haber decodificado el
    /// bloque: saltarse la decodificación descoloca HPACK para todo lo que venga después.
    malformado: Option<&'static str>,
    /// Lo que la petición dijo que iba a mandar, y lo que lleva mandado. `H2-043`: si no cuadran,
    /// dos intermediarios leen dos cuerpos distintos, que es el contrabando de HTTP/1.1 otra vez.
    declarado: Option<u64>,
    recibido: u64,
}

/// Los topes que convierten «tramas válidas» en «demasiadas tramas válidas».
///
/// No son ajustes del protocolo: son política de este servidor. Se eligen holgados para no
/// estorbar a un cliente normal y finitos para que uno anormal choque con algo.
#[derive(Debug, Clone, Copy)]
pub struct Topes {
    /// `H2-036`: octetos de un bloque de cabeceras repartido en CONTINUATION.
    pub bloque_cabeceras: usize,
    /// `H2-037`: flujos anulados por el cliente antes de que el trabajo llegue a nada.
    pub anulados: u32,
    /// `H2-038`: tramas de control seguidas sin que se abra ningún flujo.
    pub control_sin_flujo: u32,
}

impl Default for Topes {
    fn default() -> Topes {
        Topes { bloque_cabeceras: 64 * 1024, anulados: 200, control_sin_flujo: 1_000 }
    }
}

/// El estado de una conexión HTTP/2. Vive tanto como el socket: la tabla de HPACK y los
/// identificadores de flujo no se pueden reiniciar a mitad.
pub struct Sesion {
    /// Los nuestros. Gobiernan la entrada, y por eso no los toca nadie más: si el cliente pudiera
    /// moverlos, subiría por SETTINGS los topes que lo contienen.
    pub ajustes: Ajustes,
    /// Los del cliente. Gobiernan lo que se le manda, y los mueve él.
    pub par: Ajustes,
    pub topes: Topes,
    decodificador: Decodificador,
    flujos: HashMap<u32, Flujo>,
    /// El mayor identificador que ha llegado. `H2-007`: nunca se puede retroceder.
    ultimo: u32,
    /// Ventana de entrada de la conexión entera, aparte de la de cada flujo.
    ventana: i64,
    /// Un bloque de cabeceras a medias: flujo, octetos y si traía `END_STREAM`.
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

    /// `H2-001`: un preámbulo que no sea el preámbulo no se atiende.
    ///
    /// No devuelve GOAWAY a propósito. En un puerto compartido con HTTP/1.1 unos octetos que no son
    /// el preámbulo son una petición HTTP/1.1 con un método que no existe, y la respuesta correcta
    /// es 501 en texto: contestar binario a un cliente que habla texto es peor que fallar.
    pub fn es_preambulo(octetos: &[u8]) -> bool {
        octetos.len() >= PREAMBULO.len() && &octetos[..PREAMBULO.len()] == PREAMBULO
    }

    /// `H2-046`. Cuántos flujos cuentan para el tope: todos los que siguen vivos, incluidos
    /// los que ya dijeron `END_STREAM` y esperan respuesta. Contar solo los abiertos parecía más
    /// justo —a uno que ya terminó de hablar no le debemos nada— y es justo al revés: ese es
    /// precisamente el que tiene trabajo en marcha. Con ese filtro el tope no llegaba a tocar nunca.
    pub fn activos(&self) -> usize {
        self.flujos.len()
    }

    pub fn ventana_de(&self, flujo: u32) -> Option<i64> {
        self.flujos.get(&flujo).map(|f| f.ventana)
    }

    /// Digiere una trama. Un `Err` se lleva la conexión; un `Cortado` dentro de `Ok` solo el flujo.
    pub fn recibir(&mut self, t: Trama) -> Result<(Vec<Accion>, Option<Cortado>), FalloConexion> {
        t.comprobar()?;

        // Un bloque de cabeceras a medias no admite nada por medio: entre HEADERS y su última
        // CONTINUATION no cabe ni un PING. Si cupiera, dos bloques podrían entrelazarse y HPACK
        // dejaría de tener un orden (§6.2).
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
            // Un tipo que no se conoce se ignora, no se rechaza (§4.1). Es lo que permite extender
            // el protocolo sin romper a quien no lo conoce.
            _ => Ok((Vec::new(), None)),
        }
    }

    /// `H2-038`: las tramas de control no abren flujos, así que nada las limita salvo un tope.
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
        // Los suyos, no los nuestros: lo que dice aquí es lo que él admite recibir. La ventana
        // inicial que anuncia es la de salida de cada flujo, y por eso el delta sale hacia fuera en
        // vez de moverle nada a la entrada.
        let delta = self.par.aplicar(&t.carga)?;
        let mut acciones = vec![Accion::Escribir(Trama::settings_ack())];
        if delta != 0 {
            acciones.push(Accion::Reajustar(delta));
        }
        Ok((acciones, None))
    }

    /// `H2-028`: misma carga, ACK puesto.
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
            // El incremento cero sobre la conexión (`H2-013`) ya lo cazó `comprobar`.
            self.control()?;
            return Ok((vec![Accion::Credito { flujo: 0, cuanto }], None));
        }
        // `H2-045`: un flujo que todavía no ha existido no admite nada, ni siquiera crédito.
        if t.flujo > self.ultimo {
            return Err(conexion(Error::Protocolo, "WINDOW_UPDATE sobre un flujo ocioso"));
        }
        // Incremento cero sobre un flujo corta el flujo, no la conexión (§6.9).
        if cuanto == 0 {
            return Ok((
                vec![Accion::Escribir(Trama::rst(t.flujo, Error::Protocolo))],
                Some(Cortado { flujo: t.flujo, codigo: Error::Protocolo, porque: "incremento cero" }),
            ));
        }
        Ok((vec![Accion::Credito { flujo: t.flujo, cuanto }], None))
    }

    /// `H2-044` y `H2-049`. El 9113 §5.3.1 deprecó el esquema de prioridades, así que la trama se
    /// lee y se tira. Lo que no se puede tirar es su forma: un flujo que depende de sí mismo no
    /// describe un árbol, y el RFC lo sigue pidiendo rechazar aunque nadie use la dependencia.
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

    /// `H2-037`: abrir y anular en bucle pide trabajo sin coste propio — CVE-2023-44487.
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
            // `H2-047`: el cliente ya dijo `END_STREAM`, así que por su lado no queda nada por
            // decir. Ni siquiera trailers: los trailers van **antes** del fin, no después.
            Some(f) if f.estado == Estado::MitadCerradoRemoto => {
                let porque = "cabeceras después del fin de flujo";
                return Ok((
                    vec![Accion::Escribir(Trama::rst(t.flujo, Error::FlujoCerrado))],
                    Some(Cortado { flujo: t.flujo, codigo: Error::FlujoCerrado, porque }),
                ));
            }
            Some(f) if f.cabeceras_vistas => {
                // `H2-017`: un segundo bloque solo vale como trailers, y los trailers cierran.
                if !t.fin_flujo() {
                    return Err(conexion(Error::Protocolo, "segundo bloque de cabeceras sin fin de flujo"));
                }
                true
            }
            Some(_) => false,
            None => {
                // `H2-007`: los identificadores solo suben. Reusar uno haría ambiguo de qué
                // petición se habla, y el cliente y el servidor dejarían de referirse a lo mismo.
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
                        // `H2-031`: la ventana del flujo nuevo es la negociada, no los 65 535 del RFC.
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

    /// `H2-032` por el lado de entrada, y `H2-008` y `H2-036` por el de los rechazos.
    fn continuation(&mut self, t: Trama) -> Result<(Vec<Accion>, Option<Cortado>), FalloConexion> {
        let (flujo, mut bloque, fin) = match self.pendiente.take() {
            Some(x) => x,
            // `H2-008`: CONTINUATION sin un HEADERS delante.
            None => return Err(conexion(Error::Protocolo, "CONTINUATION sin HEADERS delante")),
        };
        bloque.extend_from_slice(&t.carga);
        // `H2-036`: un bloque sin fin, trama a trama, es trabajo infinito por un octeto —
        // CVE-2024-27316. El tope va en el total acumulado, que es lo único que lo ve venir.
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
        // Se decodifica **siempre**, incluso para tirarlo: la tabla de HPACK es estado compartido
        // con el cliente, y saltarse un bloque la descoloca para todos los siguientes (`H2-035`).
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

        // `H2-018` a `H2-026`: una petición malformada corta **el flujo**. La conexión no tiene la
        // culpa de que una de las peticiones que lleva venga mal escrita.
        if let Err(porque) = validar(&cabeceras) {
            return Ok((self.cortar(flujo, porque), Some(Cortado { flujo, codigo: Error::Protocolo, porque })));
        }

        // `H2-043`: un `content-length` que no se puede leer ya es mentira, y con `END_STREAM` en
        // las cabeceras el cuerpo mide cero diga lo que diga.
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

    /// Un fallo de flujo: se anula, y la conexión sigue con las demás peticiones del mismo cliente.
    fn cortar(&mut self, flujo: u32, _porque: &'static str) -> Vec<Accion> {
        self.flujos.remove(&flujo);
        vec![Accion::Escribir(Trama::rst(flujo, Error::Protocolo))]
    }

    fn data(&mut self, t: Trama) -> Result<(Vec<Accion>, Option<Cortado>), FalloConexion> {
        let tamano = t.carga.len() as i64;
        // La ventana de la conexión se consume con lo que llega por el cable, relleno incluido:
        // descontar solo los datos útiles dejaría al cliente mandar relleno gratis (§6.9.1).
        self.ventana -= tamano;
        if self.ventana < 0 {
            return Err(conexion(Error::ControlDeFlujo, "el cliente se pasó de la ventana de la conexión"));
        }

        let datos = recortar(&t)?;
        let estado = match self.flujos.get_mut(&t.flujo) {
            Some(f) => {
                f.ventana -= tamano;
                if f.ventana < 0 {
                    return Err(conexion(Error::ControlDeFlujo, "el cliente se pasó de la ventana del flujo"));
                }
                if f.estado == Estado::MitadCerradoRemoto {
                    // Cuerpo después de `END_STREAM`: el flujo está cerrado por su lado.
                    return Ok((
                        vec![Accion::Escribir(Trama::rst(t.flujo, Error::FlujoCerrado))],
                        Some(Cortado { flujo: t.flujo, codigo: Error::FlujoCerrado, porque: "DATA tras fin de flujo" }),
                    ));
                }
                if t.fin_flujo() {
                    f.estado = Estado::MitadCerradoRemoto;
                }
                f.recibido += tamano as u64;
                // `H2-043`: pasarse se sabe al momento; quedarse corto, solo al final.
                let miente = match f.declarado {
                    Some(d) => f.recibido > d || (t.fin_flujo() && f.recibido != d),
                    None => false,
                };
                if miente {
                    let porque = "el cuerpo no mide lo que dijo content-length";
                    return Ok((self.cortar(t.flujo, porque), Some(Cortado { flujo: t.flujo, codigo: Error::Protocolo, porque })));
                }
                Some(f.estado)
            }
            None => None,
        };
        if estado.is_none() {
            // Un flujo que nunca existió es error de conexión; uno ya cerrado, de flujo.
            if t.flujo > self.ultimo {
                return Err(conexion(Error::Protocolo, "DATA sobre un flujo que nunca se abrió"));
            }
            return Ok((
                vec![Accion::Escribir(Trama::rst(t.flujo, Error::FlujoCerrado))],
                Some(Cortado { flujo: t.flujo, codigo: Error::FlujoCerrado, porque: "DATA sobre un flujo cerrado" }),
            ));
        }

        // Se devuelve crédito en cuanto se ha leído. Esperar a que la aplicación consuma el cuerpo
        // convierte un handler lento en un atasco de toda la conexión.
        let mut acciones = vec![Accion::Cuerpo { flujo: t.flujo, datos, fin: t.fin_flujo() }];
        if tamano > 0 {
            self.ventana += tamano;
            acciones.push(Accion::Escribir(Trama::ventana(0, tamano as u32)));
            if !t.fin_flujo() {
                if let Some(f) = self.flujos.get_mut(&t.flujo) {
                    f.ventana += tamano;
                }
                acciones.push(Accion::Escribir(Trama::ventana(t.flujo, tamano as u32)));
            }
        }
        Ok((acciones, None))
    }

    /// Se acabó de responder: nuestra mitad se cierra, el flujo **no**. Sigue vivo hasta que el
    /// cliente diga `END_STREAM`, porque hasta entonces lo que llegue todavía hay que validarlo.
    pub fn respondido(&mut self, flujo: u32) {
        if let Some(f) = self.flujos.get(&flujo) {
            if f.estado == Estado::MitadCerradoRemoto {
                self.flujos.remove(&flujo);
            }
        }
    }
}

/// El bloque de prioridad de un HEADERS que lo traiga, saltándose el octeto de relleno si lo hay.
fn prioridad_de(t: &Trama) -> Option<&[u8]> {
    if t.banderas & PRIORIDAD == 0 {
        return None;
    }
    let desde = if t.banderas & RELLENO != 0 { 1 } else { 0 };
    t.carga.get(desde..desde + 4)
}

/// `content-length` tal como lo declaró la petición. Lo que no es un número no es un tamaño.
fn declarado(cabeceras: &[(String, String)]) -> Result<Option<u64>, &'static str> {
    match cabeceras.iter().find(|(n, _)| n == "content-length") {
        None => Ok(None),
        Some((_, v)) => v.parse().map(Some).map_err(|_| "content-length que no es un número"),
    }
}

/// Los cuatro primeros octetos de un bloque de prioridad son el flujo del que se depende, con el
/// bit de exclusividad arriba.
fn depende_de_si_mismo(carga: &[u8], flujo: u32) -> bool {
    carga.len() >= 4 && u32::from_be_bytes([carga[0] & 0x7f, carga[1], carga[2], carga[3]]) == flujo
}

/// Quita el relleno de una trama que lo declare, comprobando que quepa. `H2-004` y §6.1: un
/// relleno mayor que la carga es un error de protocolo, no un recorte silencioso.
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
        // El octeto que dice cuánto relleno hay va **antes** del posible bloque de prioridad.
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

/// Las reglas de forma de una petición en HTTP/2, §8.2 y §8.3. Devuelve por qué está malformada.
///
/// Se comprueban aquí y no en el ruteo a propósito: una petición malformada no llega a existir como
/// petición, así que ninguna capa de arriba puede decidir mal sobre ella.
fn validar(cabeceras: &[(String, String)]) -> Result<(), &'static str> {
    let mut metodo = false;
    let mut esquema = false;
    let mut camino = false;
    let mut vistos: Vec<&str> = Vec::new();
    let mut ya_hubo_normal = false;

    for (nombre, valor) in cabeceras {
        // `H2-019`: en HTTP/2 los nombres van en minúscula, y una mayúscula no se corrige — se
        // trata como malformada. Corregirla dejaría que dos intermediarios leyeran cabeceras
        // distintas de los mismos octetos.
        if nombre.chars().any(|c| c.is_ascii_uppercase()) {
            return Err("nombre de campo con mayúsculas");
        }
        if nombre.is_empty() {
            return Err("nombre de campo vacío");
        }
        if nombre.starts_with(':') {
            // `H2-023`: los pseudo-campos van todos delante.
            if ya_hubo_normal {
                return Err("pseudo-campo después de un campo normal");
            }
            // `H2-024`: uno desconocido no se ignora. Ignorarlo dejaría al cliente meter semántica
            // que este servidor no ve y el siguiente sí.
            if !PSEUDO.contains(&nombre.as_str()) {
                return Err("pseudo-campo desconocido");
            }
            if nombre == ":status" {
                return Err(":status en una petición");
            }
            // `H2-022`: repetido es malformado, no «gana el último».
            if vistos.contains(&nombre.as_str()) {
                return Err("pseudo-campo repetido");
            }
            vistos.push(nombre.as_str());
            match nombre.as_str() {
                ":method" => metodo = true,
                ":scheme" => esquema = true,
                ":path" => {
                    // `H2-021`: un `:path` vacío no es la raíz.
                    if valor.is_empty() {
                        return Err(":path vacío");
                    }
                    camino = true;
                }
                _ => {}
            }
        } else {
            ya_hubo_normal = true;
            // `H2-025`: las cabeceras específicas de la conexión no existen en HTTP/2. `keep-alive`
            // y compañía hablan de una conexión que aquí no es de una sola petición.
            if matches!(
                nombre.as_str(),
                "connection" | "keep-alive" | "proxy-connection" | "transfer-encoding" | "upgrade"
            ) {
                return Err("cabecera específica de conexión");
            }
            // `H2-026`: `TE` solo admite `trailers`.
            if nombre == "te" && valor.trim() != "trailers" {
                return Err("TE con un valor distinto de trailers");
            }
        }
    }
    // `H2-020`: sin método, esquema o camino no hay petición que rutear.
    if !metodo || !esquema || !camino {
        return Err("falta :method, :scheme o :path");
    }
    Ok(())
}

/// Las cabeceras de una respuesta, listas para HPACK.
///
/// `H2-033` y `H2-034`: los nombres salen en minúscula y las de conexión no salen. La segunda no es
/// cosmética — un `Connection: keep-alive` emitido en h2 hace que un proxy que traduzca a HTTP/1.1
/// escriba una conexión que no existe.
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
