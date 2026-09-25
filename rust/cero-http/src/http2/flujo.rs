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
    pub ajustes: Ajustes,
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

    pub fn flujos_abiertos(&self) -> usize {
        self.flujos.len()
    }

    /// Cuántos flujos cuentan para el tope de concurrencia. Uno al que solo le falta que el cliente
    /// diga `END_STREAM` ya no nos cuesta trabajo, así que no debe cerrar la puerta a otro.
    pub fn activos(&self) -> usize {
        self.flujos.values().filter(|f| f.estado == Estado::Abierto).count()
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
            // PRIORITY está deprecado por el 9113 §5.3.1: se lee y se tira.
            PRIORITY => Ok((Vec::new(), None)),
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
        let delta = self.ajustes.aplicar(&t.carga)?;
        // §6.9.2: cambiar la ventana inicial mueve la de los flujos ya abiertos, no solo la de los
        // siguientes. Olvidarlo deja al cliente y al servidor contando distinto.
        if delta != 0 {
            for f in self.flujos.values_mut() {
                f.ventana += delta;
            }
        }
        self.decodificador.max_lista = self.ajustes.max_cabeceras as usize;
        Ok((vec![Accion::Escribir(Trama::settings_ack())], None))
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
            self.control()?;
            return Ok((Vec::new(), None));
        }
        // Incremento cero sobre un flujo corta el flujo, no la conexión (§6.9).
        if cuanto == 0 {
            return Ok((
                vec![Accion::Escribir(Trama::rst(t.flujo, Error::Protocolo))],
                Some(Cortado { flujo: t.flujo, codigo: Error::Protocolo, porque: "incremento cero" }),
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

        if let Some(f) = self.flujos.get_mut(&flujo) {
            f.cabeceras_vistas = true;
            if fin {
                f.estado = Estado::MitadCerradoRemoto;
            }
        }
        if trailers {
            return Ok((vec![Accion::Cuerpo { flujo, datos: Vec::new(), fin: true }], None));
        }

        // `H2-018` a `H2-026`: una petición malformada corta **el flujo**. La conexión no tiene la
        // culpa de que una de las peticiones que lleva venga mal escrita.
        if let Err(porque) = validar(&cabeceras) {
            self.flujos.remove(&flujo);
            return Ok((
                vec![Accion::Escribir(Trama::rst(flujo, Error::Protocolo))],
                Some(Cortado { flujo, codigo: Error::Protocolo, porque }),
            ));
        }
        Ok((vec![Accion::Peticion { flujo, cabeceras, fin }], None))
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
