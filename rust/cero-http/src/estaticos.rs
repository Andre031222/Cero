use crate::contexto::Respuesta;
use std::fs;
use std::io;
use std::path::{Component, Path, PathBuf};

pub struct Estaticos {
    raiz: PathBuf,
    pub respaldo: Option<String>,
}

impl Estaticos {
    pub fn en(raiz: &str) -> io::Result<Estaticos> {
        Ok(Estaticos { raiz: Path::new(raiz).canonicalize()?, respaldo: None })
    }

    pub fn con_respaldo(mut self, archivo: &str) -> Estaticos {
        self.respaldo = Some(archivo.into());
        self
    }

    pub fn servir(&self, camino: &str) -> Respuesta {
        let relativo = camino.trim_start_matches('/');
        let limpio: PathBuf = Path::new(relativo)
            .components()
            .filter(|c| matches!(c, Component::Normal(_)))
            .collect();
        let destino = self.raiz.join(&limpio);

        let Ok(real) = destino.canonicalize() else {
            return self.respaldar();
        };
        if !real.starts_with(&self.raiz) {
            return Respuesta::estado(403, "fuera de la raíz");
        }
        match fs::read(&real) {
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
        let pagina = self.respaldo.as_ref().and_then(|n| fs::read(self.raiz.join(n)).ok());
        match pagina {
            Some(cuerpo) => Respuesta {
                estado: 200,
                tipo: "text/html; charset=utf-8".into(),
                cuerpo,
                extra: Vec::new(),
            },
            None => Respuesta::estado(404, "no encontrado"),
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
        _ => "application/octet-stream",
    }
}
