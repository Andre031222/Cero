//! El contrato de datos, probado entero **sin base de datos**.
//!
//! Que esto sea posible es la prueba de que el módulo define el contrato y no depende de ningún
//! motor, igual que `cero-data` en Java no declara driver de ejecución.

use cero_data::memoria::EnMemoria;
use cero_data::migraciones::{self, Migracion};
use cero_data::{transaccion, Conexion, Fallo, Fila, Fuente, Repositorio, Valor};

fn sembrada() -> EnMemoria {
    let m = EnMemoria::nueva();
    m.sembrar(
        "nota",
        vec![
            Fila::de(vec![("id", Valor::Entero(1)), ("titulo", "primera".into())]),
            Fila::de(vec![("id", Valor::Entero(2)), ("titulo", "segunda".into())]),
            Fila::de(vec![("id", Valor::Entero(3)), ("titulo", "tercera".into())]),
        ],
    );
    m
}

#[test]
fn la_fila_se_lee_por_nombre_de_columna() {
    let f = Fila::de(vec![("id", Valor::Entero(7)), ("titulo", "hola".into())]);
    assert_eq!(f.entero("id"), Some(7));
    assert_eq!(f.texto("titulo").as_deref(), Some("hola"));
    assert_eq!(f.texto("no_existe"), None);
    assert_eq!(f.columnas(), vec!["id", "titulo"]);
}

#[test]
fn un_nulo_no_es_una_cadena_vacia() {
    let f = Fila::de(vec![("x", Valor::Nulo)]);
    assert_eq!(f.texto("x"), None, "nulo es ausencia, no cadena vacía");
}

#[test]
fn el_repositorio_busca_por_clave() {
    let m = sembrada();
    let r = Repositorio::nuevo(&m, "nota", "id").expect("identificadores válidos");
    let f = r.por_clave(Valor::Entero(2)).unwrap().expect("existe");
    assert_eq!(f.texto("titulo").as_deref(), Some("segunda"));
    assert!(r.por_clave(Valor::Entero(99)).unwrap().is_none(), "lo que no está devuelve None");
}

#[test]
fn el_repositorio_rechaza_identificadores_que_no_puede_parametrizar() {
    let m = EnMemoria::nueva();
    // Un nombre de tabla no puede ir como parámetro en SQL. Si viene de fuera y no se comprueba,
    // es inyección — y por eso se comprueba al construir y no al consultar.
    for malo in ["nota; DROP TABLE x", "nota'", "", "no tabla"] {
        assert!(Repositorio::nuevo(&m, malo, "id").is_err(), "debería rechazar: {malo:?}");
    }
    assert!(Repositorio::nuevo(&m, "nota_2", "id").is_ok(), "lo normal pasa");
}

#[test]
fn la_transaccion_confirma_cuando_todo_va_bien() {
    let m = sembrada();
    let borradas = transaccion(&m, |c| c.ejecutar("DELETE FROM nota WHERE id = ?", &[Valor::Entero(1)]))
        .expect("confirma");
    assert_eq!(borradas, 1);
    assert_eq!(m.cuantas("nota"), 2, "el borrado quedó");
}

#[test]
fn la_transaccion_deshace_cuando_el_cuerpo_falla() {
    let m = sembrada();
    let r: Result<(), Fallo> = transaccion(&m, |c| {
        c.ejecutar("DELETE FROM nota WHERE id = ?", &[Valor::Entero(1)])?;
        c.ejecutar("DELETE FROM nota WHERE id = ?", &[Valor::Entero(2)])?;
        Err(Fallo("algo salió mal a mitad".into()))
    });
    assert!(r.is_err());
    assert_eq!(m.cuantas("nota"), 3, "no quedó ni el primer borrado");
}

#[test]
fn partir_por_punto_y_coma_es_lo_que_falla() {
    // El caso exacto que rompía la implementación ingenua: un punto y coma dentro de una cadena.
    let guion = "INSERT INTO t VALUES ('a;b');\nDELETE FROM t WHERE x = 1;";
    let s = migraciones::sentencias(guion);
    assert_eq!(s.len(), 2, "dos sentencias, no tres · {s:?}");
    assert!(s[0].contains("'a;b'"), "la cadena llega entera · {}", s[0]);
}

#[test]
fn los_comentarios_no_generan_sentencias() {
    let guion = "-- esto explica algo; y lleva punto y coma\nSELECT 1;";
    let s = migraciones::sentencias(guion);
    assert_eq!(s.len(), 1, "{s:?}");
    assert!(!s[0].contains("explica"), "el comentario no viaja");
}

#[test]
fn las_migraciones_se_aplican_en_orden_y_una_sola_vez() {
    let m = sembrada();
    let mut c = m.conexion().unwrap();
    let pendientes = vec![
        Migracion { version: 2, nombre: "dos".into(),
                    guion: "DELETE FROM nota WHERE id = 2;".into() },
        Migracion { version: 1, nombre: "uno".into(),
                    guion: "DELETE FROM nota WHERE id = 1;".into() },
    ];
    let hechas = migraciones::aplicar(c.as_mut(), pendientes, &[]).unwrap();
    assert_eq!(hechas, 2);
    assert_eq!(m.cuantas("nota"), 1, "las dos se aplicaron");

    let repetida = vec![Migracion { version: 1, nombre: "uno".into(),
                                    guion: "DELETE FROM nota WHERE id = 3;".into() }];
    assert_eq!(migraciones::aplicar(c.as_mut(), repetida, &[1]).unwrap(), 0,
               "una ya aplicada no se repite");
    assert_eq!(m.cuantas("nota"), 1);
}
