//! Archivos estáticos.
//!
//! Servir un directorio es la primera forma de abrir un agujero: `GET /../../etc/passwd`. El
//! camino se resuelve y se comprueba que sigue dentro de la raíz **después** de resolverlo, que
//! es lo único que ataja los enlaces simbólicos además del `..`.

use crate::contexto::Respuesta;
use std::path::{Component, Path, PathBuf};

pub struct Estaticos {
    raiz: PathBuf,
    /// Si el camino no existe, se sirve esto. Para aplicaciones de una sola página, `index.html`.
    pub respaldo: Option<String>,
}

impl Estaticos {
    pub fn en(raiz: &str) -> std::io::Result<Estaticos> {
        Ok(Estaticos { raiz: Path::new(raiz).canonicalize()?, respaldo: None })
    }

    pub fn con_respaldo(mut self, archivo: &str) -> Estaticos {
        self.respaldo = Some(archivo.into());
        self
    }

    pub fn servir(&self, camino: &str) -> Respuesta {
        let relativo = camino.trim_start_matches('/');
        // Se descartan `..` y las raíces antes de tocar el disco: así un camino hostil no llega
        // siquiera a resolverse.
        let limpio: PathBuf = Path::new(relativo)
            .components()
            .filter(|c| matches!(c, Component::Normal(_)))
            .collect();
        let destino = self.raiz.join(&limpio);

        let Ok(real) = destino.canonicalize() else {
            return self.respaldar();
        };
        // La comprobación que de verdad importa: tras resolver enlaces, ¿sigue dentro?
        if !real.starts_with(&self.raiz) {
            return Respuesta::estado(403, "fuera de la raíz");
        }
        match std::fs::read(&real) {
            Ok(bytes) => Respuesta {
                estado: 200,
                tipo: tipo_de(&real).into(),
                cuerpo: bytes,
                extra: vec![("Cache-Control".into(), "public, max-age=3600".into())],
            },
            Err(_) => self.respaldar(),
        }
    }

    fn respaldar(&self) -> Respuesta {
        let Some(nombre) = &self.respaldo else {
            return Respuesta::estado(404, "no encontrado");
        };
        match std::fs::read(self.raiz.join(nombre)) {
            Ok(bytes) => Respuesta { estado: 200, tipo: "text/html; charset=utf-8".into(),
                                     cuerpo: bytes, extra: Vec::new() },
            Err(_) => Respuesta::estado(404, "no encontrado"),
        }
    }
}

fn tipo_de(ruta: &Path) -> &'static str {
    match ruta.extension().and_then(|e| e.to_str()).unwrap_or("") {
        "html" | "htm" => "text/html; charset=utf-8",
        "css" => "text/css; charset=utf-8",
        "js" | "mjs" => "text/javascript; charset=utf-8",
        "json" => "application/json; charset=utf-8",
        "svg" => "image/svg+xml",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "webp" => "image/webp",
        "woff2" => "font/woff2",
        "txt" | "md" => "text/plain; charset=utf-8",
        // Lo que no se reconoce va como octetos, nunca adivinando: adivinar el tipo es lo que
        // `X-Content-Type-Options: nosniff` existe para impedir del lado del navegador.
        _ => "application/octet-stream",
    }
}
