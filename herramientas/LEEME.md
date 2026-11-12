# Herramientas

## comentarios.py

Cero no lleva comentarios en el código: la explicación vive en los MD. Esta herramienta quita los comentarios y vigila que no vuelvan. Usa solo la biblioteca estándar de Python 3.

```
python3 herramientas/comentarios.py --quitar java rust c++
python3 herramientas/comentarios.py --verificar java rust c++
```

- `--quitar` reescribe los archivos sin comentarios e imprime cuáles cambió y cuántos quitó.
- `--verificar` no escribe nada. Imprime `archivo:línea` por cada comentario y sale con 1 si encuentra alguno, con 0 si no.
- Extensiones: `.java`, `.rs`, `.cpp`, `.hpp`, `.h`, `.py`. Ignora `target/`, `build/`, `node_modules/` y `.git/`.
- Sale con 2 si el uso es incorrecto o un archivo no se puede leer o analizar.

### Cómo distingue un comentario de un texto

Java, Rust y C++ pasan por un léxico por estados, no por expresiones regulares. Reconoce:

- cadenas con escapes, y caracteres (`'/'`);
- bloques de texto de Java (`"""`);
- cadenas crudas de Rust (`r"..."`, `r#"..."#`, `br#"..."#`) y de C++ (`R"x(...)x"`);
- los *lifetimes* de Rust (`'a`), que no abren un carácter;
- los separadores de dígitos de C++ (`1'000'000`);
- los comentarios de bloque anidados de Rust;
- los comentarios de línea de C++ que continúan con `\`.

Las directivas `#include` y `#pragma` no son comentarios y se conservan.

Python usa `tokenize` para los `#` y `ast` para los docstrings de módulo, clase y función. Si un docstring es el único cuerpo de una clase o función, queda un `pass`. Se conservan el *shebang* y la línea de codificación.

### Qué queda después de quitar

Un comentario que ocupa una línea entera se lleva la línea. Uno que va al final de una línea se lleva también los espacios que lo precedían. No se generan dos líneas en blanco seguidas donde antes no las había. Los finales de línea `\r\n` se respetan.

## pruebas_comentarios.py

```
python3 -I herramientas/pruebas_comentarios.py
```

## prueba-cero-new.sh

Crea con `./cero new` un proyecto en cada lenguaje dentro de una carpeta temporal, fuera del
repositorio, lo compila, lo arranca en un puerto libre de 127.0.0.1 y comprueba que `GET /`
responde. Antes de compilar mira que la dependencia del proyecto generado apunte a GitHub, y no a
una carpeta del repositorio.

```
./herramientas/prueba-cero-new.sh              # los cuatro
./herramientas/prueba-cero-new.sh rust c++     # solo esos
```

Para no depender de que el código esté subido, compila contra este repositorio: Rust con un
`[patch]` pasado por `--config` y C++ con `FETCHCONTENT_SOURCE_DIR_CERO`. Python instala el proyecto
generado con `pip` en un venv desechable, con la línea de `requirements.txt` cambiada a
`git+file://` del repositorio (que toma lo último commiteado, no lo que haya sin guardar). Java usa
la `0.9.0-SNAPSHOT` que `cero new` deja instalada en `~/.m2`. Hacen falta Maven y JDK 25, cargo,
cmake, Python 3.12 y curl. Integración continua la corre en el trabajo `ejemplos`.

Con `CERO_DESDE_GITHUB=1` no hay ninguna de esas desviaciones: Rust, C++ y Python bajan Cero de
GitHub, como le pasará a quien lo use. Sirve para comprobarlo una vez subido el código:

```
CERO_DESDE_GITHUB=1 ./herramientas/prueba-cero-new.sh
```
