//! HPACK (RFC 7541): la compresión de cabeceras de HTTP/2.
//!
//! Tres piezas encadenadas: los enteros de longitud variable (§5.1), las cadenas —literales o en
//! Huffman— (§5.2) y la tabla de índices, estática más dinámica (§2.3).
//!
//! Lo que hace a HPACK distinto de un descompresor cualquiera es que **el estado es compartido y
//! ordenado**: la tabla dinámica del decodificador tiene que quedar exactamente igual que la del
//! codificador del otro lado después de cada bloque. Por eso un bloque que no se pueda decodificar
//! rompe la conexión entera y no solo el flujo —`H2-015`, `H2-016`—: seguir sería interpretar las
//! cabeceras siguientes contra una tabla desalineada, y eso no da un error, da cabeceras
//! equivocadas.
//!
//! Y por eso también los trailers se decodifican aunque se tiren: saltárselos descoloca la tabla.

use super::hpack_tablas::{CODIGO, EOS, ESTATICA, LARGO};
use super::trama::{Error, FalloConexion};

/// El coste de una entrada en la tabla dinámica, RFC 7541 §4.1: los dos textos más 32 octetos de
/// estructura. Es una convención del RFC, no una medida: los dos extremos tienen que contar igual.
const COSTE_FIJO: usize = 32;

fn mal(porque: &'static str) -> FalloConexion {
    FalloConexion { codigo: Error::Compresion, porque }
}

type Resultado<T> = Result<T, FalloConexion>;

/// La tabla dinámica: las últimas cabeceras vistas, en orden de llegada, con un tope en octetos.
///
/// El índice 1 del protocolo es la entrada más **reciente**, no la más antigua, así que se guarda
/// del revés de como se lee y se cuenta desde el final.
pub struct TablaDinamica {
    entradas: Vec<(String, String)>,
    ocupado: usize,
    tope: usize,
    /// Lo que la otra punta puede pedir con una actualización de tamaño. `SETTINGS_HEADER_TABLE_SIZE`
    /// lo fija; una actualización que lo supere es un error de compresión, §6.3.
    tope_maximo: usize,
}

impl TablaDinamica {
    pub fn nueva(tope: usize) -> TablaDinamica {
        TablaDinamica { entradas: Vec::new(), ocupado: 0, tope, tope_maximo: tope }
    }

    pub fn len(&self) -> usize {
        self.entradas.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entradas.is_empty()
    }

    pub fn ocupado(&self) -> usize {
        self.ocupado
    }

    fn coste(nombre: &str, valor: &str) -> usize {
        nombre.len() + valor.len() + COSTE_FIJO
    }

    /// Mete una entrada y desaloja por el otro extremo hasta que quepa.
    ///
    /// Una entrada que no cabe ni en la tabla vacía **vacía la tabla y no se guarda** (§4.4). No es
    /// un error: es lo que deja las dos tablas iguales.
    pub fn meter(&mut self, nombre: String, valor: String) {
        let coste = TablaDinamica::coste(&nombre, &valor);
        while self.ocupado + coste > self.tope {
            match self.entradas.pop() {
                Some((n, v)) => self.ocupado -= TablaDinamica::coste(&n, &v),
                None => return,
            }
        }
        self.ocupado += coste;
        self.entradas.insert(0, (nombre, valor));
    }

    /// Una actualización de tamaño dinámico (§6.3). Reducir obliga a desalojar en el acto.
    pub fn redimensionar(&mut self, nuevo: usize) -> Resultado<()> {
        if nuevo > self.tope_maximo {
            return Err(mal("actualización de tabla mayor que la negociada"));
        }
        self.tope = nuevo;
        while self.ocupado > self.tope {
            match self.entradas.pop() {
                Some((n, v)) => self.ocupado -= TablaDinamica::coste(&n, &v),
                None => break,
            }
        }
        Ok(())
    }

    fn en(&self, indice: usize) -> Option<(&str, &str)> {
        self.entradas.get(indice).map(|(n, v)| (n.as_str(), v.as_str()))
    }
}

/// Un decodificador vive tanto como la conexión: su tabla es el estado compartido con el cliente.
pub struct Decodificador {
    pub tabla: TablaDinamica,
    /// `H2-039`: el tope de lo que sale, no de lo que entra. Tres kilobytes comprimidos pueden ser
    /// trescientos al expandirse, y limitar solo el bloque no lo ve venir.
    pub max_lista: usize,
}

impl Decodificador {
    pub fn nuevo(tope_tabla: usize, max_lista: usize) -> Decodificador {
        Decodificador { tabla: TablaDinamica::nueva(tope_tabla), max_lista }
    }

    /// Decodifica un bloque entero. Devuelve las cabeceras en el orden en que venían: el orden de
    /// los valores repetidos de un mismo campo es significativo, §3.2.2 del 9113.
    pub fn decodificar(&mut self, bloque: &[u8]) -> Resultado<Vec<(String, String)>> {
        let mut salida = Vec::new();
        let mut tamano = 0usize;
        let mut i = 0usize;
        // Una actualización de tamaño solo vale al principio del bloque (§4.2).
        let mut admite_actualizacion = true;

        while i < bloque.len() {
            let primero = bloque[i];
            if primero & 0x80 != 0 {
                // Indexado entero: nombre y valor salen de la tabla.
                admite_actualizacion = false;
                let indice = leer_entero(bloque, &mut i, 7)?;
                if indice == 0 {
                    return Err(mal("índice 0 en una referencia indexada"));
                }
                let (n, v) = self.buscar(indice)?;
                tamano += n.len() + v.len() + COSTE_FIJO;
                salida.push((n, v));
            } else if primero & 0x40 != 0 {
                admite_actualizacion = false;
                let (n, v) = self.literal(bloque, &mut i, 6)?;
                tamano += n.len() + v.len() + COSTE_FIJO;
                self.tabla.meter(n.clone(), v.clone());
                salida.push((n, v));
            } else if primero & 0x20 != 0 {
                if !admite_actualizacion {
                    return Err(mal("actualización de tabla fuera del principio del bloque"));
                }
                let nuevo = leer_entero(bloque, &mut i, 5)?;
                self.tabla.redimensionar(nuevo)?;
                continue;
            } else {
                // 0x10 es «nunca indexar»; para decodificar se trata igual que «sin indexar». La
                // diferencia solo obliga a quien reenvía, y aquí no se reenvía nada.
                admite_actualizacion = false;
                let (n, v) = self.literal(bloque, &mut i, 4)?;
                tamano += n.len() + v.len() + COSTE_FIJO;
                salida.push((n, v));
            }
            if tamano > self.max_lista {
                return Err(mal("la lista de cabeceras se pasa del máximo al expandirse"));
            }
        }
        Ok(salida)
    }

    /// `H2-015`: un índice fuera de las dos tablas rompe la conexión.
    fn buscar(&self, indice: usize) -> Resultado<(String, String)> {
        if indice <= ESTATICA.len() {
            let (n, v) = ESTATICA[indice - 1];
            return Ok((n.to_string(), v.to_string()));
        }
        match self.tabla.en(indice - ESTATICA.len() - 1) {
            Some((n, v)) => Ok((n.to_string(), v.to_string())),
            None => Err(mal("índice de HPACK fuera de la tabla")),
        }
    }

    fn literal(&mut self, bloque: &[u8], i: &mut usize, bits: u8) -> Resultado<(String, String)> {
        let indice = leer_entero(bloque, i, bits)?;
        let nombre = if indice == 0 {
            leer_cadena(bloque, i)?
        } else {
            self.buscar(indice)?.0
        };
        let valor = leer_cadena(bloque, i)?;
        Ok((nombre, valor))
    }
}

/// Entero de longitud variable, §5.1. El prefijo lleva `bits` útiles; si están todos a uno, siguen
/// septetos con el bit alto como continuación.
///
/// El tope de 5 septetos no es decorativo: sin él, una tira de octetos `0xff` describe un entero
/// arbitrariamente grande y el bucle no termina.
fn leer_entero(bloque: &[u8], i: &mut usize, bits: u8) -> Resultado<usize> {
    let mascara = (1usize << bits) - 1;
    if *i >= bloque.len() {
        return Err(mal("entero HPACK cortado"));
    }
    let mut valor = (bloque[*i] as usize) & mascara;
    *i += 1;
    if valor < mascara {
        return Ok(valor);
    }
    let mut desplazamiento = 0u32;
    loop {
        if *i >= bloque.len() {
            return Err(mal("entero HPACK cortado"));
        }
        if desplazamiento > 28 {
            return Err(mal("entero HPACK demasiado grande"));
        }
        let octeto = bloque[*i];
        *i += 1;
        valor += ((octeto & 0x7f) as usize) << desplazamiento;
        desplazamiento += 7;
        if octeto & 0x80 == 0 {
            return Ok(valor);
        }
    }
}

/// Cadena, §5.2: un bit de «viene en Huffman», la longitud en el mismo octeto y los datos.
fn leer_cadena(bloque: &[u8], i: &mut usize) -> Resultado<String> {
    if *i >= bloque.len() {
        return Err(mal("cadena HPACK cortada"));
    }
    let huffman = bloque[*i] & 0x80 != 0;
    let largo = leer_entero(bloque, i, 7)?;
    if *i + largo > bloque.len() {
        return Err(mal("cadena HPACK cortada"));
    }
    let datos = &bloque[*i..*i + largo];
    *i += largo;
    let octetos = if huffman { descomprimir(datos)? } else { datos.to_vec() };
    // Un valor de cabecera es una tira de octetos, no texto: la conversión perdida convertiría
    // bytes inválidos en '\u{fffd}' calladamente, y dos cabeceras distintas pasarían a ser iguales.
    String::from_utf8(octetos).map_err(|_| mal("cadena HPACK que no es UTF-8"))
}

/// Huffman, §5.2. Se recorre bit a bit contra la tabla: 257 símbolos de hasta 30 bits no justifican
/// un árbol, y el bucle plano es el que se puede leer al lado del RFC.
fn descomprimir(datos: &[u8]) -> Resultado<Vec<u8>> {
    let mut salida = Vec::with_capacity(datos.len() * 8 / 5);
    let mut acumulado: u64 = 0;
    let mut bits: u32 = 0;

    for &octeto in datos {
        acumulado = (acumulado << 8) | octeto as u64;
        bits += 8;
        while bits >= 5 {
            match casar(acumulado, bits) {
                Some((simbolo, largo)) => {
                    if simbolo == EOS {
                        // H2-016: EOS dentro de la cadena, no como relleno.
                        return Err(mal("EOS dentro de una cadena Huffman"));
                    }
                    salida.push(simbolo as u8);
                    bits -= largo;
                    acumulado &= (1u64 << bits) - 1;
                }
                None => break,
            }
        }
    }

    // Lo que queda tiene que ser relleno: menos de ocho bits y todos a uno, §5.2. Un relleno más
    // largo o con un cero es un símbolo que se quedó a medias, y eso ya no es la misma cadena.
    if bits >= 8 {
        return Err(mal("relleno Huffman de ocho bits o más"));
    }
    if bits > 0 {
        let todos_uno = (1u64 << bits) - 1;
        if acumulado != todos_uno {
            return Err(mal("relleno Huffman que no son unos"));
        }
    }
    Ok(salida)
}

/// El símbolo más corto que casa con los bits de la cabeza, o `None` si aún no hay bastantes.
fn casar(acumulado: u64, bits: u32) -> Option<(usize, u32)> {
    for simbolo in 0..=EOS {
        let largo = LARGO[simbolo] as u32;
        if largo > bits {
            continue;
        }
        if (acumulado >> (bits - largo)) as u32 == CODIGO[simbolo] {
            return Some((simbolo, largo));
        }
    }
    None
}

// ── Codificar ───────────────────────────────────────────────────────────────────────────────
//
// El servidor solo codifica sus propias respuestas, y ahí la tabla dinámica aporta poco: las
// cabeceras que se repiten entre respuestas —`content-type`, `server`— ya están en la estática.
// Se codifica sin indexar, que deja la tabla del cliente quieta y hace la salida reproducible.

pub struct Codificador;

impl Codificador {
    /// Un bloque con las cabeceras dadas. Los nombres se pasan a minúsculas: en HTTP/2 una
    /// mayúscula en un nombre de campo es un mensaje malformado, §8.2.1.
    pub fn codificar(cabeceras: &[(String, String)]) -> Vec<u8> {
        let mut salida = Vec::new();
        for (nombre, valor) in cabeceras {
            let minusculas = nombre.to_ascii_lowercase();
            match indice_exacto(&minusculas, valor) {
                Some(i) => {
                    escribir_entero(&mut salida, i, 7, 0x80);
                }
                None => match indice_de_nombre(&minusculas) {
                    Some(i) => {
                        escribir_entero(&mut salida, i, 4, 0x00);
                        escribir_cadena(&mut salida, valor);
                    }
                    None => {
                        salida.push(0x00);
                        escribir_cadena(&mut salida, &minusculas);
                        escribir_cadena(&mut salida, valor);
                    }
                },
            }
        }
        salida
    }
}

fn indice_exacto(nombre: &str, valor: &str) -> Option<usize> {
    ESTATICA
        .iter()
        .position(|(n, v)| *n == nombre && *v == valor)
        .map(|i| i + 1)
}

fn indice_de_nombre(nombre: &str) -> Option<usize> {
    ESTATICA.iter().position(|(n, _)| *n == nombre).map(|i| i + 1)
}

fn escribir_entero(salida: &mut Vec<u8>, valor: usize, bits: u8, bandera: u8) {
    let mascara = (1usize << bits) - 1;
    if valor < mascara {
        salida.push(bandera | valor as u8);
        return;
    }
    salida.push(bandera | mascara as u8);
    let mut resto = valor - mascara;
    while resto >= 0x80 {
        salida.push((resto as u8 & 0x7f) | 0x80);
        resto >>= 7;
    }
    salida.push(resto as u8);
}

/// Se escribe en Huffman solo si sale más corto. Comprimir cuando no comprime es gastar CPU por
/// octetos de más, y el RFC lo deja a elección de quien codifica (§5.2).
fn escribir_cadena(salida: &mut Vec<u8>, texto: &str) {
    let comprimido = comprimir(texto.as_bytes());
    if comprimido.len() < texto.len() {
        escribir_entero(salida, comprimido.len(), 7, 0x80);
        salida.extend_from_slice(&comprimido);
    } else {
        escribir_entero(salida, texto.len(), 7, 0x00);
        salida.extend_from_slice(texto.as_bytes());
    }
}

fn comprimir(datos: &[u8]) -> Vec<u8> {
    let mut salida = Vec::new();
    let mut acumulado: u64 = 0;
    let mut bits: u32 = 0;
    for &octeto in datos {
        let simbolo = octeto as usize;
        let largo = LARGO[simbolo] as u32;
        acumulado = (acumulado << largo) | CODIGO[simbolo] as u64;
        bits += largo;
        while bits >= 8 {
            salida.push((acumulado >> (bits - 8)) as u8);
            bits -= 8;
            acumulado &= (1u64 << bits) - 1;
        }
    }
    if bits > 0 {
        // El relleno son los bits altos de EOS, que son todos unos.
        salida.push(((acumulado << (8 - bits)) | ((1u64 << (8 - bits)) - 1)) as u8);
    }
    salida
}
