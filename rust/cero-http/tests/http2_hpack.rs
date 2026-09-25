//! HPACK contra el apéndice C del RFC 7541, más los requisitos de `spec/http2.md` que le tocan.
//!
//! Los vectores del apéndice son la única comprobación que no se puede escribir «de acuerdo con lo
//! que hace el código»: los octetos y el estado de la tabla vienen dados, y la tabla dinámica
//! después de cada paso está en el RFC. Un decodificador que acierte el resultado pero deje la
//! tabla distinta pasaría cualquier prueba propia y fallaría en la petición siguiente.

use cero_http::http2::hpack::{Codificador, Decodificador};
use cero_http::http2::hpack_tablas::{CODIGO, ESTATICA, LARGO};

fn hex(s: &str) -> Vec<u8> {
    let limpio: String = s.chars().filter(|c| c.is_ascii_hexdigit()).collect();
    (0..limpio.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&limpio[i..i + 2], 16).unwrap())
        .collect()
}

fn d() -> Decodificador {
    Decodificador::nuevo(4096, 64 * 1024)
}

fn pares(v: &[(&str, &str)]) -> Vec<(String, String)> {
    v.iter().map(|(n, x)| (n.to_string(), x.to_string())).collect()
}

// ── Las tablas ──────────────────────────────────────────────────────────────────────────────

/// La suma de Kraft de un código prefijo completo vale exactamente 1. Es lo que demuestra que no
/// falta ni sobra un símbolo en las 257 filas; una tabla mal copiada no revienta, decodifica mal.
#[test]
fn el_codigo_de_huffman_es_un_codigo_prefijo_completo() {
    let suma: f64 = LARGO.iter().map(|&l| 2f64.powi(-(l as i32))).sum();
    assert!((suma - 1.0).abs() < 1e-12, "suma de Kraft = {suma}");
    assert_eq!(CODIGO.len(), 257);
    assert_eq!(ESTATICA.len(), 61);
    assert_eq!(ESTATICA[0].0, ":authority");
    assert_eq!(ESTATICA[60], ("www-authenticate", ""));
}

// ── Apéndice C.2: las cuatro formas de representar una cabecera ─────────────────────────────

#[test]
fn c21_literal_con_nombre_literal_e_indexado() {
    let mut dec = d();
    let salida = dec
        .decodificar(&hex("400a 6375 7374 6f6d 2d6b 6579 0d63 7573 746f 6d2d 6865 6164 6572"))
        .unwrap();
    assert_eq!(salida, pares(&[("custom-key", "custom-header")]));
    assert_eq!(dec.tabla.len(), 1);
    assert_eq!(dec.tabla.ocupado(), 55);
}

#[test]
fn c22_literal_sin_indexar() {
    let mut dec = d();
    let salida = dec.decodificar(&hex("040c 2f73 616d 706c 652f 7061 7468")).unwrap();
    assert_eq!(salida, pares(&[(":path", "/sample/path")]));
    assert!(dec.tabla.is_empty(), "sin indexar no toca la tabla");
}

#[test]
fn c23_literal_que_nunca_se_indexa() {
    let mut dec = d();
    let salida = dec.decodificar(&hex("1008 7061 7373 776f 7264 0673 6563 7265 74")).unwrap();
    assert_eq!(salida, pares(&[("password", "secret")]));
    assert!(dec.tabla.is_empty());
}

#[test]
fn c24_indexado() {
    let mut dec = d();
    assert_eq!(dec.decodificar(&hex("82")).unwrap(), pares(&[(":method", "GET")]));
}

// ── Apéndice C.3: tres peticiones seguidas, sin Huffman ─────────────────────────────────────
//
// Es el caso que de verdad ejercita la tabla: la segunda petición se apoya en lo que dejó la
// primera, y la tercera en las dos. Decodificarlas por separado no prueba nada.

#[test]
fn c3_tres_peticiones_encadenadas_sin_huffman() {
    let mut dec = d();

    let uno = dec.decodificar(&hex("8286 8441 0f77 7777 2e65 7861 6d70 6c65 2e63 6f6d")).unwrap();
    assert_eq!(
        uno,
        pares(&[(":method", "GET"), (":scheme", "http"), (":path", "/"), (":authority", "www.example.com")])
    );
    assert_eq!(dec.tabla.ocupado(), 57);

    let dos = dec.decodificar(&hex("8286 84be 5808 6e6f 2d63 6163 6865")).unwrap();
    assert_eq!(
        dos,
        pares(&[
            (":method", "GET"),
            (":scheme", "http"),
            (":path", "/"),
            (":authority", "www.example.com"),
            ("cache-control", "no-cache"),
        ])
    );
    assert_eq!(dec.tabla.ocupado(), 110);

    let tres = dec
        .decodificar(&hex("8287 85bf 400a 6375 7374 6f6d 2d6b 6579 0c63 7573 746f 6d2d 7661 6c75 65"))
        .unwrap();
    assert_eq!(
        tres,
        pares(&[
            (":method", "GET"),
            (":scheme", "https"),
            (":path", "/index.html"),
            (":authority", "www.example.com"),
            ("custom-key", "custom-value"),
        ])
    );
    assert_eq!(dec.tabla.ocupado(), 164);
    assert_eq!(dec.tabla.len(), 3);
}

// ── Apéndice C.4: las mismas tres, con Huffman ──────────────────────────────────────────────

#[test]
fn c4_tres_peticiones_encadenadas_con_huffman() {
    let mut dec = d();

    let uno = dec.decodificar(&hex("8286 8441 8cf1 e3c2 e5f2 3a6b a0ab 90f4 ff")).unwrap();
    assert_eq!(uno[3], (":authority".to_string(), "www.example.com".to_string()));
    assert_eq!(dec.tabla.ocupado(), 57);

    let dos = dec.decodificar(&hex("8286 84be 5886 a8eb 1064 9cbf")).unwrap();
    assert_eq!(dos[4], ("cache-control".to_string(), "no-cache".to_string()));
    assert_eq!(dec.tabla.ocupado(), 110);

    let tres = dec
        .decodificar(&hex("8287 85bf 4088 25a8 49e9 5ba9 7d7f 8925 a849 e95b b8e8 b4bf"))
        .unwrap();
    assert_eq!(tres[4], ("custom-key".to_string(), "custom-value".to_string()));
    assert_eq!(dec.tabla.ocupado(), 164);
}

// ── Apéndice C.5: respuestas con una tabla de 256 octetos, que obliga a desalojar ───────────

#[test]
fn c5_respuestas_con_tabla_pequena_desalojan() {
    let mut dec = Decodificador::nuevo(256, 64 * 1024);

    let uno = dec
        .decodificar(&hex(
            "4803 3330 3258 0770 7269 7661 7465 611d 4d6f 6e2c 2032 3120 4f63 7420 3230 3133 2032 303a 3133 3a32 3120 474d 546e 1768 7474 7073 3a2f 2f77 7777 2e65 7861 6d70 6c65 2e63 6f6d",
        ))
        .unwrap();
    assert_eq!(uno[0], (":status".to_string(), "302".to_string()));
    assert_eq!(dec.tabla.ocupado(), 222);

    let dos = dec.decodificar(&hex("4803 3330 37c1 c0bf")).unwrap();
    assert_eq!(dos[0], (":status".to_string(), "307".to_string()));
    assert_eq!(dec.tabla.ocupado(), 222, "entra una y sale otra");

    let tres = dec
        .decodificar(&hex(
            "88c1 611d 4d6f 6e2c 2032 3120 4f63 7420 3230 3133 2032 303a 3133 3a32 3220 474d 54c0 5a04 677a 6970 7738 666f 6f3d 4153 444a 4b48 514b 425a 584f 5157 454f 5049 5541 5851 5745 4f49 553b 206d 6178 2d61 6765 3d33 3630 303b 2076 6572 7369 6f6e 3d31",
        ))
        .unwrap();
    assert_eq!(tres[0], (":status".to_string(), "200".to_string()));
    assert_eq!(dec.tabla.ocupado(), 215);
    assert_eq!(dec.tabla.len(), 3, "la tabla de 256 no da para más");
}

// ── Apéndice C.6: las mismas respuestas, con Huffman ────────────────────────────────────────

#[test]
fn c6_respuestas_con_huffman_y_desalojo() {
    let mut dec = Decodificador::nuevo(256, 64 * 1024);

    dec.decodificar(&hex(
        "4882 6402 5885 aec3 771a 4b61 96d0 7abe 9410 54d4 44a8 2005 9504 0b81 66e0 82a6 2d1b ff6e 919d 29ad 1718 63c7 8f0b 97c8 e9ae 82ae 43d3",
    ))
    .unwrap();
    assert_eq!(dec.tabla.ocupado(), 222);

    dec.decodificar(&hex("4883 640e ffc1 c0bf")).unwrap();
    assert_eq!(dec.tabla.ocupado(), 222);

    let tres = dec
        .decodificar(&hex(
            "88c1 6196 d07a be94 1054 d444 a820 0595 040b 8166 e084 a62d 1bff c05a 839b d9ab 77ad 94e7 821d d7f2 e6c7 b335 dfdf cd5b 3960 d5af 2708 7f36 72c1 ab27 0fb5 291f 9587 3160 65c0 03ed 4ee5 b106 3d50 07",
        ))
        .unwrap();
    assert_eq!(tres[0], (":status".to_string(), "200".to_string()));
    assert_eq!(dec.tabla.ocupado(), 215);
}

// ── Los requisitos del contrato ─────────────────────────────────────────────────────────────

/// `H2-015`: un índice fuera de las dos tablas. Con la dinámica vacía, 62 ya no existe.
#[test]
fn h2_015_indice_fuera_de_la_tabla() {
    assert!(d().decodificar(&[0xbe]).is_err(), "H2-015");
    assert!(d().decodificar(&[0x80]).is_err(), "el índice 0 tampoco vale");
}

/// `H2-016`: EOS es relleno, nunca un símbolo. Treinta bits de unos son EOS entero.
#[test]
fn h2_016_eos_dentro_de_una_cadena() {
    // Cadena Huffman de cuatro octetos con EOS completo (30 unos) más relleno.
    let bloque = [0x00, 0x00, 0x84, 0xff, 0xff, 0xff, 0xff];
    assert!(d().decodificar(&bloque).is_err(), "H2-016");
}

/// `H2-039`: el tope se mide en lo que sale, no en lo que entra. Una entrada grande en la tabla
/// dinámica y cien referencias de un octeto caben en 3 kB de cable y no en el tope de salida.
#[test]
fn h2_039_la_lista_que_se_expande_al_descomprimirse() {
    let mut dec = Decodificador::nuevo(4096, 2048);
    let mut bloque = Vec::new();
    // Literal indexado con nombre nuevo y un valor de 1000 octetos.
    bloque.push(0x40);
    bloque.push(0x03);
    bloque.extend_from_slice(b"big");
    bloque.push(0x7f); // 1000, con prefijo de siete bits: 1000 - 127 = 873
    bloque.push(0xe9);
    bloque.push(0x06);
    bloque.extend(std::iter::repeat_n(b'a', 1000));
    // Y cien referencias a esa entrada, a un octeto cada una.
    bloque.extend(std::iter::repeat_n(0x80 | 62, 100));
    let fallo = dec.decodificar(&bloque).unwrap_err();
    assert_eq!(fallo.codigo, cero_http::http2::Error::Compresion, "H2-039");
}

/// Una actualización de tamaño que se pasa de lo negociado es error de compresión (§6.3), y una
/// que llega a mitad del bloque también (§4.2).
#[test]
fn las_actualizaciones_de_tabla_tienen_su_sitio_y_su_tope() {
    let mut dec = Decodificador::nuevo(4096, 64 * 1024);
    assert!(dec.decodificar(&[0x20, 0x82]).is_ok(), "vaciar y luego indexar sí vale");

    let mut grande = Decodificador::nuevo(256, 64 * 1024);
    assert!(grande.decodificar(&[0x3f, 0xe1, 0x1f]).is_err(), "8192 > 256");

    let mut tarde = Decodificador::nuevo(4096, 64 * 1024);
    assert!(tarde.decodificar(&[0x82, 0x20]).is_err(), "la actualización va al principio");
}

/// Reducir la tabla desaloja en el acto, no cuando toque meter algo. Si no, la otra punta y esta
/// dejan de contar lo mismo y los índices se corren.
#[test]
fn reducir_la_tabla_desaloja_en_el_acto() {
    let mut dec = Decodificador::nuevo(4096, 64 * 1024);
    dec.decodificar(&hex("8286 8441 0f77 7777 2e65 7861 6d70 6c65 2e63 6f6d")).unwrap();
    assert_eq!(dec.tabla.len(), 1);
    dec.tabla.redimensionar(0).unwrap();
    assert!(dec.tabla.is_empty());
    assert_eq!(dec.tabla.ocupado(), 0);
}

/// Un entero que no termina nunca es una tira de `0xff`. Sin tope, el bucle tampoco termina.
#[test]
fn un_entero_sin_fin_no_cuelga() {
    let mut bloque = vec![0x7f];
    bloque.extend(std::iter::repeat_n(0xffu8, 20));
    assert!(d().decodificar(&bloque).is_err());
}

/// Una cadena que dice medir más de lo que queda se corta, no se lee de más.
#[test]
fn una_cadena_cortada_no_lee_de_mas() {
    assert!(d().decodificar(&[0x00, 0x0a, b'h', b'i']).is_err());
}

// ── Codificar ───────────────────────────────────────────────────────────────────────────────

/// Lo que se codifica se vuelve a leer igual: es la comprobación que cubre los dos caminos a la
/// vez, incluido el relleno de Huffman, que es donde se equivoca uno.
#[test]
fn lo_codificado_se_decodifica_igual() {
    let cabeceras = pares(&[
        (":status", "200"),
        ("content-type", "text/html; charset=utf-8"),
        ("content-length", "1234"),
        ("x-propia", "ñandú y un valor largo que comprime bien porque se repite y se repite"),
    ]);
    let bloque = Codificador::codificar(&cabeceras);
    assert_eq!(d().decodificar(&bloque).unwrap(), cabeceras);
}

/// Una cabecera que está entera en la estática ocupa un octeto. Si deja de ser así, la respuesta
/// más común del servidor engorda en cada petición.
#[test]
fn una_entrada_de_la_estatica_ocupa_un_octeto() {
    assert_eq!(Codificador::codificar(&pares(&[(":status", "200")])), vec![0x88]);
}

/// Las mayúsculas en un nombre de campo son un mensaje malformado en HTTP/2 (§8.2.1), así que no
/// pueden salir de aquí aunque quien llame las escriba.
#[test]
fn los_nombres_salen_en_minusculas() {
    let bloque = Codificador::codificar(&pares(&[("Content-Type", "text/plain")]));
    let leidas = d().decodificar(&bloque).unwrap();
    assert_eq!(leidas[0].0, "content-type");
}

/// Codificar no toca la tabla del cliente: se emite sin indexar a propósito, y eso tiene que
/// seguir siendo verdad porque es lo que hace la salida reproducible.
#[test]
fn codificar_no_mueve_la_tabla_de_quien_lee() {
    let bloque = Codificador::codificar(&pares(&[("x-una", "cosa")]));
    let mut dec = d();
    dec.decodificar(&bloque).unwrap();
    assert!(dec.tabla.is_empty());
}
