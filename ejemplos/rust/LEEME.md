# Empezar en Rust

Una API de tres rutas con Cero, sin más dependencias que `cero-http`, que a su vez solo usa `std`.

## En un proyecto nuevo

```bash
cargo new mi-api && cd mi-api
cargo add cero-http --git https://github.com/Andre031222/Cero
```

Eso deja en `Cargo.toml` la línea:

```toml
cero-http = { git = "https://github.com/Andre031222/Cero" }
```

Cargo busca el crate dentro del repositorio, así que no hace falta indicar la carpeta `rust/`.
Después, `src/main.rs`:

```rust
use cero_http::{Json, Respuesta, Servidor};
use std::{env, io};

fn main() -> io::Result<()> {
    let puerto = env::var("PUERTO").ok().and_then(|p| p.parse().ok()).unwrap_or(8080);
    Servidor::vacio()
        .get("/", |_| Respuesta::json(Json::objeto(vec![("hola", "mundo".into())])))
        .get("/saludo/{nombre}", |c| {
            let nombre = c.variable("nombre").unwrap_or("mundo");
            Respuesta::json(Json::objeto(vec![("saludo", format!("hola, {nombre}").into())]))
        })
        .post("/eco", |c| Ok(Respuesta::json(c.cuerpo_json()?)))
        .escuchar(puerto)
}
```

Y arrancarlo:

```bash
cargo run --release
curl localhost:8080/
curl localhost:8080/saludo/ana
curl -d '{"a":1}' localhost:8080/eco
```

Las respuestas son `{"hola":"mundo"}`, `{"saludo":"hola, ana"}` y el mismo JSON que se mandó. Un
cuerpo que no es JSON da 400. En PowerShell, sin depender de cómo pase las comillas a `curl.exe`:

```powershell
Invoke-RestMethod localhost:8080/eco -Method Post -Body '{"a":1}'
```

`PUERTO` cambia el puerto: `PUERTO=9000 cargo run` en un shell POSIX, `$env:PUERTO=9000; cargo run`
en PowerShell. El ejemplo escucha en todas las interfaces. Con `.direccion("127.0.0.1")` antes de
`escuchar` solo responde en local, y así macOS y Windows no preguntan por el cortafuegos.

## Las rutas en una línea

`get`, `post`, `put`, `delete` y `patch` registran la ruta y su acción en una sola llamada. Si un
patrón está mal escrito, por ejemplo `/saludo/{nombre` sin cerrar, `escuchar` devuelve un error que
nombra el patrón, en lugar de arrancar con una ruta de menos. Sin socket, `Servidor::responder`
contesta 500 con el mismo motivo.

La forma larga sigue igual: `Router::nuevo().ruta("GET", "/", "raiz")?` con
`Servidor::nuevo(router).accion("raiz", …)`. Las dos se pueden mezclar en el mismo servidor.

## Esta carpeta

Aquí `Cargo.toml` usa `path = "../../rust/cero-http"`, para que la prueba corra contra el código del
repositorio y no contra la última versión subida. `tests/punta_a_punta.rs` arranca el ejemplo en un
puerto libre de `127.0.0.1` y comprueba las tres rutas:

```bash
cargo test
```

## Memoria

En reposo, recién arrancado y sin haber atendido ninguna petición, el ejemplo ocupa **1,7 MB de
memoria residente** (1728 KB, igual en tres arranques). Tras una petición sube a 1,8 MB (1824 KB).
Medido en macOS 26.6 sobre arm64, con Rust 1.96.1 y el binario de `--release`:

```bash
cargo build --release
PUERTO=18080 ./target/release/ejemplo & P=$!; sleep 1; ps -o rss= -p $P; kill $P
```
