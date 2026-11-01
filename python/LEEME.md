# Cero en Python

Cuarta implementación del contrato de [`spec/`](../spec). Como las de Rust y C++, no es una
traducción del código de otro lenguaje: la referencia son los requisitos numerados, y el juez son
los mismos vectores de conformidad, que son bytes sobre un socket y no saben en qué lenguaje está
escrito quien responde.

**Estado: hito 2.** HTTP/1.1, ruteo y sesiones. **23 de 23 vectores del banco** y 100 pruebas
propias.

| Hito | Qué cubre | Estado |
|---|---|---|
| 1 | HTTP/1.1 y ruteo | 23 de 23 vectores · `HTTP-001`–`HTTP-023`, `RUT-001`–`RUT-013` |
| 1 bis | Un solo archivo | `python3 -m cero.empaquetar` · 3 pruebas |
| 2 | Sesiones | 12 de 13, más `SES-010` a medias hasta que haya HTTP/2 · 50 pruebas |

## Usarlo

**Sin pip, sin venv, sin requirements.txt.** Cero solo usa la biblioteca estándar, Python 3.12 o
posterior, así que no hay nada que instalar ni que aislar. Una aplicación y el framework caben en un
solo archivo, el equivalente de `java -jar app.jar`:

```bash
python3 -m cero.empaquetar miapp/ app.pyz app:main
python3 app.pyz 8080
```

`cero.empaquetar` está hecho sobre `zipapp`: copia la carpeta de la aplicación y el paquete `cero`
y los cierra en un `.pyz`. La entrada es `modulo:funcion`; si se omite, la carpeta tiene que traer
su `__main__.py`. Deja fuera `__pycache__`, los `.pyz` viejos y los archivos ocultos, para que un
`.env` no viaje dentro, y del framework solo lleva lo que se ejecuta: ni `empaquetar.py` ni
`conforme.py`. Se niega a seguir enlaces simbólicos (uno a `~/.ssh` acabaría dentro) y a escribir la
salida dentro de la carpeta que empaqueta. Lo que no sea oculto viaja: un `secrets.json` o una base
SQLite junto al código se empaquetan, así que no se dejan ahí. La prueba lo arranca con `python3 -I`, que no mira ni `site-packages` ni un venv: si
funciona así, no depende de nada instalado.

`miapp/app.py` registra sus rutas a mano y arranca el servidor:

```python
import sys

from cero.ruta import Router
from cero.servidor import Servidor


def main():
    router = Router()
    router.agregar("GET", "/usuarios/{id}", lambda peticion: "usuario " + peticion.variables["id"])
    Servidor(router).escuchar(int(sys.argv[1]))
```

Una acción devuelve una cadena (texto plano), unos bytes (`application/octet-stream`), `None`
(204) o una `Respuesta` hecha a mano.

`escuchar` atiende solo en `127.0.0.1` salvo que se le pida otra cosa: para salir de la máquina
es `escuchar(8080, "0.0.0.0")`, y la decisión queda a la vista.

Las pruebas y el banco:

```bash
cd python
python3 -I -m unittest discover -s pruebas -t .   # las pruebas propias
python3 -m cero.conforme 8777                     # y el servidor del banco
python3 ../spec/banco/correr.py 127.0.0.1 8777
```

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

Un hilo por conexión significa que un cliente lento retiene un hilo, así que los límites son parte
de la decisión y no un detalle. Todos se ajustan al construir el servidor:

```python
Servidor(router, maximo_conexiones=256, espera=30, plazo_cabecera=10, plazo_cuerpo=60,
         limite_cuerpo=1024 * 1024)
```

| Límite | Defecto | Qué corta |
|---|---|---|
| `maximo_conexiones` | 256 | la siguiente recibe 503, y se lee lo que mandó antes de cerrar |
| `plazo_cabecera` | 10 s | la línea de petición y las cabeceras, **en total**: 408 |
| `plazo_cuerpo` | 60 s | el cuerpo entero, en total: 408 |
| `espera` | 30 s | cada lectura suelta, y cada escritura de la respuesta |
| `limite_cuerpo` | 1 MiB | 413 |

**El plazo es por petición, no por lectura.** La primera versión solo tenía `espera`, y un cliente
que mandaba un byte cada 29 segundos retenía el hilo para siempre. Con 512 de esos el servidor
contestaba 503 a todo el mundo, desde una sola máquina y con un guion de cinco líneas. Ahora el
socket se lee a través de un reloj que, antes de cada `recv`, ajusta la espera a lo que le queda al
plazo de esa petición.

El techo de memoria es el producto de dos límites: 256 conexiones por 1 MiB son 256 MiB de cuerpos
en vuelo. El cuerpo se acumula en un `bytearray` que crece por porciones de 64 KiB, sin la lista de
trozos más el `join` que duplicaba el pico.

`maximo_conexiones` es 256 y no 512 porque en macOS el límite blando de descriptores es 256.
Aunque se pase, el bucle de `accept` ya no muere: ante `EMFILE`, `ENFILE`, `ENOBUFS` o `ENOMEM`
espera una décima y sigue, y si un hilo no arranca se devuelve el cupo y se cierra esa conexión.
Hay una prueba que arranca el servidor con 40 descriptores y le abre 60 conexiones.

## Sesiones

Una acción pide la sesión a la petición. `sesion()` la recupera por la cookie o da `None`;
`abrir_sesion()` la crea si no hay una viva:

```python
def entrar(peticion):
    peticion.abrir_sesion().poner("usuario", "ana")
    return "dentro"

def quien(peticion):
    sesion = peticion.sesion()
    return sesion.leer("usuario") if sesion else "nadie"
```

`rotar()` cambia el identificador y conserva los atributos, `invalidar()` la borra del almacén, y
una sesión invalidada lanza `SesionInvalidada` al leerla, escribirla o rotarla (`SES-004`,
`SES-006`). Los atributos son cadenas en los dos almacenes, para que lo que funciona en memoria no
falle al pasar a la tabla.

**El almacén con tabla es `sqlite3`, de la biblioteca estándar.** Es una ventaja real de Python:
Rust y C++ no traen ninguna base de datos, y para `SES-012` y `SES-013` tuvieron que escribir su
propia capa de datos (`cero-data`) y esperar al hito 12. Aquí son dos clases en el mismo archivo:

```python
Servidor(router)                                              # en memoria, el defecto
Servidor(router, sesiones=Tabla("app.db", "sesiones_app"))   # en una tabla, compartida
```

`Tabla` no tiene nombre de tabla por omisión (`SES-013`) y lo valida, porque un identificador no
puede ir como parámetro en SQL: solo letras, dígitos y `_`, y nada que empiece por `sqlite_`. Dos
servidores sobre el mismo archivo se reconocen las sesiones, y hay una prueba que lo comprueba
desde otro proceso (`SES-012`). Abre una conexión por operación, así que no hay conexión
compartida entre hilos que proteger. El límite es el de SQLite: un archivo en una máquina, no un
almacén en red.

**Las escrituras se funden, no se sobrescriben (`SES-007`).** Rust y C++ comparten un único objeto
sesión entre peticiones y lo protegen con un candado. Eso no sirve para una tabla, donde cada
petición lee su propia copia: la que guarde la última se llevaría por delante lo que escribió la
otra. Aquí cada petición recibe una vista y anota sus cambios clave por clave, y al guardar se
releen los atributos dentro de un `BEGIN IMMEDIATE`, se aplican los cambios y se escribe. El
almacén en memoria hace lo mismo bajo su candado, así que los dos se comportan igual. Para ver que
las pruebas no estaban vacías se cambió la fusión por una sobrescritura: fallaron las cinco de
`SES-007`, en memoria, en tabla y por el pipeline. Lo que no evita es que dos peticiones hagan
leer-sumar-escribir sobre la **misma** clave; eso es una carrera de la aplicación, no del almacén.
Y un cambio que llegue con el identificador viejo justo después de una rotación se pierde, porque
esa fila ya no existe con ese nombre.

**Solo se escribe si cambió.** Una petición que solo lee no toca la tabla; la inactividad se
renueva al leer cuando ya ha pasado la mitad del plazo, que es una escritura cada quince minutos
como mucho y no una por petición. Una prueba lo cuenta con un *trigger* de SQLite. La caducidad usa
el reloj de pared, `time.time()`, porque la fecha la leen otras instancias, y una sesión caducada
se borra al tocarla. El almacén en memoria además barre las caducadas al crear, como mucho una vez
por plazo, para que las que nadie vuelve a pedir no se acumulen.

El identificador son 32 bytes de `secrets.token_hex`, 64 caracteres (`SES-002`); una colisión,
que no va a pasar, se vuelve a sortear (`SES-003`). La cookie es
`cero_sid=…; Path=/; HttpOnly; SameSite=Lax`, y lleva `; Secure` cuando y solo cuando
`peticion.segura` es cierto (`SES-009`). Una cookie que no tenga la forma de un identificador
no llega al almacén.

Si la acción lanza, la respuesta es un 500 y **no se guarda nada ni sale cookie**: lo que la acción
escribió antes de fallar se descarta. Una sesión que se abrió en esa acción deja una fila sin dueño
que caduca sola.

### Lo que la aplicación tiene que hacer, y lo que todavía no hace Cero

- **Llamar a `rotar()` al iniciar sesión.** Si no, quien consiga plantar su cookie en el navegador
  de la víctima antes del login comparte la sesión con ella después (*session fixation*). Cero no
  sabe qué acción es el login, así que no puede rotar por su cuenta.
- **Cerrar sesión no manda una cookie caducada.** `invalidar()` borra la sesión del almacén, y la
  cookie vieja ya no recupera nada, pero el navegador la sigue enviando hasta que la cierra.
- **`Secure` depende de `peticion.segura`, y hoy nada lo pone a cierto.** El servidor no habla TLS,
  y tampoco hay manera de fiarse de un proxy de confianza que diga que delante sí había TLS. Detrás
  de un proxy con TLS, la cookie sale **sin `Secure`**.

### `SES-010`: un solo sitio, y sin HTTP/2 con que probarlo

La cookie se guarda y se emite en `Servidor.responder`, que es la función por la que pasa toda
petición sea cual sea el protocolo: lo que viene detrás solo serializa. La respuesta que lleva la
cookie es una copia, para no ensuciar una `Respuesta` que la acción tenga guardada y reutilice.

**No hay prueba que cite `SES-010`, y no cuenta.** La primera versión tenía una que llamaba a
`responder` con `HTTP/1.0`, `HTTP/1.1`, `HTTP/2` y `HTTP/3`, pero `responder` no lee la versión: la
prueba no podía fallar, y se quitó. Lo único que de verdad cambia con h2 y se puede probar ya es que
la cookie llegue partida en varias cabeceras (RFC 9113 §8.2.3), y esa prueba existe sin citar el
requisito. `spec/sesiones.md` dice que la prueba de verdad **tiene que hablar h2**, y esta
implementación no lo habla: la biblioteca estándar no lo trae y el hito de HTTP/2 no ha llegado.
Así que `SES-010` queda **a medias**: el diseño lo cumple, nada lo verifica, y no es irrepresentable.
Cuando exista la segunda salida, la prueba tiene que entrar por ella.

### `SES-011` en Python es disciplina, como en Java

Consultar la cookie pendiente la consume. En Rust el método toma `&mut self` y dos caminos que la
consulten los dos no compilan; en Python, como en Java, se puede escribir. La prueba lo vigila
contando las llamadas: una por respuesta que tiene sesión, ninguna por la que no la tiene.

### Lo que no se pudo representar

Nada. Ningún requisito de sesiones necesita algo fuera de la biblioteca estándar, y ninguno es
imposible de escribir en Python. Lo único pendiente es probar `SES-010` sobre h2, y eso depende de
un hito, no del lenguaje.

## Lo que trae la biblioteca estándar de Python

`socket` y `threading` los trae también Rust. `unittest` ahorra el corredor de pruebas propio que
C++ y Java tuvieron que escribir. Y lo que ni Rust ni C++ traen es **`ssl`** y **`sqlite3`**; el
segundo ya sostiene las sesiones en tabla. Rust y C++ dejan TLS
fuera del proceso porque escribirlo sin dependencias no se puede comprobar desde fuera. Python
lo trae enlazado a OpenSSL, así que TLS dentro del proceso deja de ser una dependencia nueva. Si se
usa o no es una decisión del hito de HTTP/2, no de este.

## El parser rechaza ante la duda

Cada ambigüedad del RFC 9112 se rechaza y se cierra la conexión, con el estado que le corresponde:
400 para lo mal formado, 501 para un método o una codificación que no se implementa, 505 para una
versión que no existe, 431 para una línea o unas cabeceras desmedidas y 413 para un cuerpo de más
del límite. `Content-Length` y `Transfer-Encoding` juntos son 400, que es el vector que Rust falló
el primer día. Un `Content-Length` vacío también es 400: aceptarlo como 0 hacía que el cuerpo se
leyera como la petición siguiente. El tamaño de un trozo no admite espacio delante, y su extensión
no admite caracteres de control.

Una línea de petición desmedida es 431 y no 414 porque así lo pide el vector del banco, aunque 414
(*URI Too Long*) sería lo más exacto. Cambiarlo es una decisión del contrato, no de esta
implementación.

El encuadre de la respuesta es del servidor: un `Content-Length`, `Transfer-Encoding` o `Connection`
que ponga la acción se descarta, para que no salgan dos. Una conexión HTTP/1.0 que pide
`keep-alive` recibe `Connection: keep-alive`, y sin ella el cliente esperaría un cierre.

`import cero` en un Python anterior a 3.12 falla con un mensaje que lo dice, en vez de con un error
de sintaxis o un `except` que no captura lo que debía.

Al rechazar, el servidor cierra su lado de escritura y **lee lo que quede** antes de cerrar del
todo. Si cerrara con datos sin leer en el búfer, el sistema mandaría un RST y el cliente podría
perder la respuesta de error antes de leerla.

Un cliente que corta a mitad de una respuesta hace que `sendall` lance `BrokenPipeError`: se
termina esa conexión y el resto sigue. Hay una prueba que lo hace tres veces seguidas y falla si
alguna excepción escapa de un hilo.

## El pipeline se puede llamar sin socket

`Servidor.responder(peticion)` toma una petición y devuelve la respuesta, la misma lección que C++
trajo de Rust. El 404, el 405 con `Allow` y el 500 se prueban así, sin abrir un puerto.

## Cómo se cuentan los requisitos

Los 23 de HTTP/1.1, por los vectores del banco. El resto, por una prueba que los cite en su nombre,
y solo en `pruebas/`:

```bash
grep -rhoiE --include='*.py' '(HTTP|OBS|RUT|SEG|SES|H2)[-_][0-9]{3}' pruebas | tr a-z_ A-Z- | sort -u
```

Hoy son 29: `RUT-001`–`RUT-013`, `SES-001`–`SES-013` salvo `SES-010`, y `HTTP-017`, `HTTP-019`, `HTTP-021` y
`HTTP-022`, que el banco ya cubre y aquí se prueban además por otro lado. Los de sesiones se
cuentan solos:

```bash
grep -rhoiE --include='*.py' 'SES[-_][0-9]{3}' pruebas | tr a-z_ A-Z- | sort -u | wc -l   # 12
```

`SES-010` no sale porque ninguna prueba lo cita: sin h2 no hay prueba que pueda fallar.
