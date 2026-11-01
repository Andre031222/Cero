# Cero en Python

Cuarta implementación del contrato de [`spec/`](../spec). Como las de Rust y C++, no es una
traducción del código de otro lenguaje: la referencia son los requisitos numerados, y el juez son
los mismos vectores de conformidad, que son bytes sobre un socket y no saben en qué lenguaje está
escrito quien responde.

**Estado: hito 1.** HTTP/1.1 y ruteo. **23 de 23 vectores del banco** y 31 pruebas propias.

| Hito | Qué cubre | Estado |
|---|---|---|
| 1 | HTTP/1.1 y ruteo | 23 de 23 vectores · `HTTP-001`–`HTTP-023`, `RUT-001`–`RUT-013` |

## Usarlo

Solo la biblioteca estándar, Python 3.12 o posterior.

```bash
cd python
python3 -I -m unittest discover -s pruebas -t .   # las pruebas propias
python3 -m cero.conforme 8777                     # y el servidor del banco
python3 ../spec/banco/correr.py 127.0.0.1 8777
```

Una aplicación registra sus rutas a mano y arranca el servidor:

```python
from cero.ruta import Router
from cero.servidor import Servidor

router = Router()
router.agregar("GET", "/usuarios/{id}", lambda peticion: "usuario " + peticion.variables["id"])
Servidor(router).escuchar(8080)
```

Una acción devuelve una cadena (texto plano), unos bytes (`application/octet-stream`), `None`
(204) o una `Respuesta` hecha a mano.

## La decisión de concurrencia: un hilo del sistema por conexión

Igual que en Rust y en C++. `asyncio` está en la biblioteca estándar y habría sido la elección
de moda, pero obliga a que todo el código de la aplicación sea asíncrono: una acción que llame a
algo bloqueante congela el bucle entero y todas las conexiones con él. Con hilos, la acción es una
función normal, y es lo que espera quien escribe Python sin pensar en el servidor.

El precio es el GIL. Los hilos de CPython no ejecutan Python en paralelo; ceden el GIL al esperar
el socket, así que una carga de E/S escala y una de cálculo no. Para un framework web la espera
domina, pero eso es una hipótesis hasta medirla.

**Pregunta abierta, para medir:** desde 3.13 existe la compilación *free-threaded* (PEP 703), sin
GIL. Este código no comparte estado mutable entre conexiones salvo el cupo, que es un semáforo, así
que debería correr igual en ella. Si escala con los núcleos es justo lo que el banco de rendimiento
tiene que contestar antes de afirmarlo.

Dos límites que en Rust y C++ no hacían falta escribir y aquí sí, porque un hilo de Python cuesta
más: como mucho 512 conexiones a la vez (la siguiente recibe 503) y 30 segundos de espera en cada
lectura del socket.

## Lo que trae la biblioteca estándar de Python

`socket` y `threading` los trae también Rust. `unittest` ahorra el corredor de pruebas propio que
C++ y Java tuvieron que escribir. Y lo que ni Rust ni C++ traen es **`ssl`**. Rust y C++ dejan TLS
fuera del proceso porque escribirlo sin dependencias no se puede comprobar desde fuera. Python
lo trae enlazado a OpenSSL, así que TLS dentro del proceso deja de ser una dependencia nueva. Si se
usa o no es una decisión del hito de HTTP/2, no de este.

## El parser rechaza ante la duda

Cada ambigüedad del RFC 9112 se rechaza y se cierra la conexión, con el estado que le corresponde:
400 para lo mal formado, 501 para un método o una codificación que no se implementa, 505 para una
versión que no existe, 431 para una línea o unas cabeceras desmedidas y 413 para un cuerpo de más
de 16 MiB. `Content-Length` y `Transfer-Encoding` juntos son 400, que es el vector que Rust falló
el primer día.

Al rechazar, el servidor cierra su lado de escritura y **lee lo que quede** antes de cerrar del
todo. Si cerrara con datos sin leer en el búfer, el sistema mandaría un RST y el cliente podría
perder la respuesta de error antes de leerla.

Un cliente que corta a mitad de una respuesta hace que `sendall` lance `BrokenPipeError`: se
termina esa conexión y el resto sigue. Hay una prueba que lo hace tres veces seguidas.

## El pipeline se puede llamar sin socket

`Servidor.responder(peticion)` toma una petición y devuelve la respuesta, la misma lección que C++
trajo de Rust. El 404, el 405 con `Allow` y el 500 se prueban así, sin abrir un puerto.

## Cómo se cuentan los requisitos

Los 23 de HTTP/1.1, por los vectores del banco. El resto, por una prueba que los cite en su nombre,
y solo en `pruebas/`:

```bash
grep -rhoiE '(HTTP|OBS|RUT|SEG|SES|H2)[-_][0-9]{3}' pruebas | tr a-z_ A-Z- | sort -u
```

Hoy son `RUT-001`–`RUT-013` y `HTTP-022`, que el banco ya cubre y aquí se prueba además con
extensiones de trozo y trailers.
