# Cero en Python

Cuarta implementación del contrato de [`spec/`](../spec). Como las de Rust y C++, no es una
traducción del código de otro lenguaje: la referencia son los requisitos numerados, y el juez son
los mismos vectores de conformidad, que son bytes sobre un socket y no saben en qué lenguaje está
escrito quien responde.

**Estado: hito 4.** HTTP/1.1, ruteo, sesiones, seguridad transversal y observabilidad. **23 de 23
vectores del banco**, 3 de 3 ataques de HTTP/1.1 y 240 pruebas propias.

| Hito | Qué cubre | Estado |
|---|---|---|
| 1 | HTTP/1.1 y ruteo | 23 de 23 vectores · `HTTP-001`–`HTTP-023`, `RUT-001`–`RUT-013` |
| 1 bis | Un solo archivo | `python3 -m cero.empaquetar` · 3 pruebas |
| 2 | Sesiones | 12 de 13, más `SES-010` a medias hasta que haya HTTP/2 · 50 pruebas |
| 3 | Seguridad transversal | 28 de 28 · `SEG-001`–`SEG-028` · 83 pruebas |
| 4 | Observabilidad | 23 de 23 · `OBS-001`–`OBS-023` · 48 pruebas |
| 4 bis | Plazos y topes | 3 de 3 · `HTTP-024`–`HTTP-026` · 3 de 3 ataques de HTTP/1.1 · 10 pruebas |

## Usarlo

**Sin pip, sin venv, sin requirements.txt.** Cero solo usa la biblioteca estándar, Python 3.12 o
posterior, así que no hay nada que instalar ni que aislar. Una aplicación y el framework caben en un
solo archivo, el equivalente de `java -jar app.jar`:

```bash
python3 -m cero.empaquetar miapp/ app.pyz app:main
python3 app.pyz 8080
```

`cero.empaquetar` está hecho sobre `zipapp`: copia la carpeta de la aplicación y el paquete `cero` y
los cierra en un `.pyz`. La entrada es `modulo:funcion`; si se omite, la carpeta tiene que traer su
`__main__.py`. Deja fuera `__pycache__`, los `.pyz` viejos y los archivos ocultos, para que un
`.env` no viaje dentro, y del framework solo lleva lo que se ejecuta: ni `empaquetar.py` ni
`conforme.py`. Se niega a seguir enlaces simbólicos (uno a `~/.ssh` acabaría dentro) y a escribir la
salida dentro de la carpeta que empaqueta. Lo que no sea oculto viaja: un `secrets.json` o una base
SQLite junto al código se empaquetan, así que no se dejan ahí. La prueba lo arranca con
`python3 -I`, que no mira ni `site-packages` ni un venv: si funciona así, no depende de nada instalado.

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
python3 -m cero.conforme 8777 > /dev/null         # y el servidor del banco
python3 ../spec/banco/correr.py 127.0.0.1 8777
python3 ../spec/banco/ataques.py --solo-http1 python3 -m cero.conforme
```

`ataques.py` arranca el servidor él mismo, con plazos de un segundo. Con `--solo-http1` corre los
tres vectores de HTTP/1.1 (`HTTP-024` a `HTTP-026`) y marca los ocho de HTTP/2 como «n/a»: esta
implementación todavía no habla h2, y h2spec tampoco aplica.

## Usar librerías de terceros con Cero

Que Cero no dependa de nada no obliga a la aplicación a lo mismo. Lo que pida la aplicación se
instala con pip, como siempre, en un venv mientras se desarrolla:

```bash
python3 -m venv .venv
.venv/bin/pip install humanize
```

Para que viaje dentro del `.pyz`, se instala en la carpeta de la aplicación con `--target`, y
`cero.empaquetar` la lleva junto al código, porque la raíz del archivo está en `sys.path`:

```bash
python3 -m pip install --target miapp humanize
python3 -m cero.empaquetar miapp/ app.pyz app:main
python3 app.pyz 8080
```

```python
import sys

import humanize

from cero.ruta import Router
from cero.servidor import Servidor


def main():
    router = Router()
    router.agregar("GET", "/tamano/{bytes}",
                   lambda peticion: humanize.naturalsize(int(peticion.variables["bytes"])))
    Servidor(router).escuchar(int(sys.argv[1]))
```

`GET /tamano/3000000` responde `3.0 MB`. Eso vale para las librerías de Python puro. Las que traen
código compilado, como numpy, no: Python no carga un `.so` ni un `.pyd` desde dentro de un zip, y
además quedarían atadas al sistema y a la versión en que se instalaron. Con esas, la aplicación se
despliega como carpeta, con su venv y el paquete `cero` al lado, y se arranca con el Python de ese
venv en vez de con el `.pyz`.

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
         inactividad=10, limite_cuerpo=10 * 1024 * 1024)
```

| Límite | Defecto | Qué corta |
|---|---|---|
| `maximo_conexiones` | 256 | la siguiente recibe 503, y se lee lo que mandó antes de cerrar |
| `plazo_cabecera` | 10 s | la línea de petición y las cabeceras, **en total**: 408 |
| `plazo_cuerpo` | 60 s | el cuerpo entero, en total: 408 |
| `espera` | 30 s | cada lectura suelta, y cada escritura de la respuesta |
| `inactividad` | 10 s | keep-alive sin una petición nueva: se cierra sin responder (`HTTP-024`) |
| `limite_cuerpo` | 10 MiB | 413 y cierre, sin que corra la acción (`HTTP-026`) |

Un valor de 0 o negativo en cualquiera de los seis es un `ValueError` al construir el servidor
(`HTTP-025`): en un socket, 0 querría decir «sin plazo», y en una cuenta atrás, «ya pasó».
`python3 -m cero.conforme <puerto> <plazo en ms>` usa ese plazo como `espera` y el doble como
`inactividad`, que es como lo arranca `ataques.py`, y con un plazo de 0 o negativo sale con 2 sin
llegar a escuchar.

La inactividad solo corre entre peticiones. La primera de una conexión espera `plazo_cabecera`, y
no `inactividad`, para que una conexión que no manda nada no retenga el hilo más que un slowloris.
Mientras se espera la siguiente, cada lectura usa `inactividad` y no `espera`, así que una
`espera` más corta no acorta el keep-alive. Los 10 s no son ni los 30 de Java ni los 120 de Rust y
C++: con un hilo por conexión, una ociosa cuesta un hilo, y 10 s era lo que ya aguantaba antes de
tener su propio ajuste.

`HTTP-026` pide además cerrar la conexión si el tope se pasa con la respuesta ya empezada. Aquí no
puede pasar: el cuerpo entero se lee antes de que corra la acción, así que el 413 siempre llega
antes que cualquier otra cosa, con `Connection: close`.

**El plazo es por petición, no por lectura.** La primera versión solo tenía `espera`, y un cliente
que mandaba un byte cada 29 segundos retenía el hilo para siempre. Con 512 de esos el servidor
contestaba 503 a todo el mundo, desde una sola máquina y con un guion de cinco líneas. Ahora el
socket se lee a través de un reloj que, antes de cada `recv`, ajusta la espera a lo que le queda al
plazo de esa petición.

El techo de memoria es el producto de dos límites: 256 conexiones por 10 MiB son 2,5 GiB de
cuerpos en vuelo. El contrato fija los 10 MiB por defecto; hasta la versión anterior Python usaba
1 MiB, y quien despliegue en una máquina pequeña puede bajar `limite_cuerpo` o
`maximo_conexiones`. El cuerpo se acumula en un `bytearray` que crece por porciones de 64 KiB, sin la lista de
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
  sabe qué acción es el login, así que no puede rotar por su cuenta. Rotar también descarta el
  token CSRF (ver «Seguridad transversal»).
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

## Seguridad transversal

`spec/seguridad.md` fija qué pasa cuando nadie ha configurado nada. Aquí, sin configurar nada,
**toda respuesta lleva las cabeceras de seguridad**, también el 404, el 405, el 500, el 429 y los
rechazos del parser y el 503 por falta de cupo, que no pasan por `responder` y se blindan aparte.
Lo demás se enciende al construir el servidor:

```python
from cero.seguridad import Cabeceras, Cors, Csrf, Limitador

Servidor(router,
         cabeceras=Cabeceras(enmarcado="SAMEORIGIN", csp="default-src 'self'"),
         cors=Cors(["https://app.pe"], credenciales=True),
         csrf=Csrf(exentas=["/webhooks"]),
         limitador=Limitador(cupo=100, ventana=60))
```

| Pieza | Por defecto | Requisitos |
|---|---|---|
| `Cabeceras` | encendida, una por servidor; `cabeceras=False` la apaga | `SEG-001`–`SEG-007` |
| `Cors` | apagada: ninguna cabecera CORS | `SEG-008`–`SEG-014` |
| `Csrf` | apagada | `SEG-015`–`SEG-019` |
| `Limitador` | apagado | `SEG-020`–`SEG-022` |
| `sanear_html`, `sanear_texto`, `sanear_nombre` | funciones de `cero.saneado` | `SEG-023`–`SEG-026` |
| `validado(esquema, accion)` | envoltorio de `cero.validacion` | `SEG-027`, `SEG-028` |

CSRF y el limitador van apagados, como en Java y en Rust: el contrato pide que
existan y cómo se comportan, no que estén encendidos, y un CSRF encendido por defecto rompe a todo
cliente que no sea un navegador. Que el defecto sea ese es una decisión, y queda a la vista.

**Cabeceras.** `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy:
strict-origin-when-cross-origin` y `Permissions-Policy` con cámara, micrófono y ubicación a `()`.
HSTS solo sale si `peticion.segura`, que hoy nada pone a cierto: por el socket no sale nunca
(`SEG-005`). No hay CSP salvo que se declare (`SEG-006`). Si la acción pone una de estas cabeceras,
gana la suya y no salen dos.

**CORS.** Un origen ajeno no recibe permiso, pero la petición simple sigue y la acción corre
(`SEG-010`); un preflight ajeno es 403 y la acción no corre (`SEG-013`). Es preflight un `OPTIONS`
con `Origin` **y** `Access-Control-Request-Method`; sin la segunda es un `OPTIONS` normal y va al
router. Un preflight de un origen admitido que pide un método o una cabecera que no están en
`metodos` o `cabeceras` también es 403: el navegador lo haría cumplir igual, pero el servidor no
anuncia permisos que no da. El comodín con credenciales no se deja construir (`ValueError`), ni como
`"*"` ni dentro de una lista: devolver el origen que llegue con credenciales es abrir la API a
cualquier sitio. Con CORS configurado, **toda** respuesta lleva `Vary: Origin`, también la que llega
sin `Origin`: la respuesta depende de que la cabecera esté, y una caché que guarde la versión sin
permiso se la serviría después al navegador que sí lo tenía (es lo que recomienda Fetch). `Vary` no
es una cabecera CORS, así que `SEG-011` se cumple; si la acción ya trae `Vary`, se le suma `Origin`.
Un 429 lleva las cabeceras CORS, para que el navegador pueda leer el motivo.

**CSRF.** Corre después del ruteo, así que un verbo no admitido sigue siendo 405 y no 403. El
token lo pide la acción con `peticion.token_csrf()`, que abre sesión si no hay y guarda en ella 32
bytes de `secrets.token_urlsafe` (`SEG-017`). Llega en la cabecera `X-CSRF-Token` o en el campo
`_csrf` de un formulario `application/x-www-form-urlencoded`, nunca en la consulta: en la URL
acabaría en los logs y en el `Referer`. Se compara con `hmac.compare_digest` (`SEG-018`); la
cabecera repetida o el campo repetido no valen. Que la comparación sea en tiempo constante se
asegura leyendo el código, no con una prueba: medir tiempos en una prueba unitaria no distingue
nada, y una prueba que espía `compare_digest` comprueba cómo está escrito, no qué hace. Las de
`SEG-017` y `SEG-018` pasan por `responder` y por el socket. La exención casa por segmento
completo, con la misma función que parte los caminos del router (`SEG-019`), y eximir la raíz
(`"/"`, `""`) no se deja construir: con cero segmentos sería prefijo de todo camino.

**`rotar()` descarta el token CSRF.** La rotación existe porque quien plantó la cookie antes del
login la conoce; si el token sobreviviera, también conocería el token, y con la cookie nueva de
la víctima en el navegador y el token viejo en su formulario el CSRF volvería a funcionar. El
siguiente `token_csrf()` da uno nuevo.

**Límite de peticiones.** Ventana fija por cliente, con `X-RateLimit-Limit`, `X-RateLimit-Remaining`
y, al pasarse, 429 con `Retry-After` (`SEG-020`, `SEG-021`). El cliente es la **dirección IP** del
otro extremo, sin el puerto: con el puerto, cada conexión nueva daría cupo nuevo. En IPv6 la clave
es el **/64**, no la dirección: a un solo equipo le suelen dar un /64 entero, 2^64 direcciones, y
con la dirección completa tendría cupo y memoria sin fin. Una IPv4 dentro de IPv6
(`::ffff:10.0.0.1`) cuenta como la IPv4. Las cuentas cuya ventana pasó se barren como mucho una vez
por ventana, y además la tabla tiene tope (`maximo`, 100 000 por defecto): llena, se echa la cuenta
cuya ventana empezó antes. El precio del tope es que, bajo una inundación de clientes distintos, a
un cliente echado se le olvida lo que llevaba gastado; la alternativa era memoria sin límite. El
límite: **detrás de un proxy
todos los clientes son el proxy**, porque Cero no se fía de `X-Forwarded-For` (no hay manera de
declarar un proxy de confianza, igual que con `Secure`).

**Saneado: una lista de lo permitido, más estricta que el contrato.** `sanear_html` usa
`html.parser` de la biblioteca estándar, que tokeniza, decodifica las entidades de los atributos
(`&#106;avascript:` llega como `javascript:`) y trata `script` y `style` como texto crudo, y sobre
esos sucesos reconstruye el HTML dejando pasar **solo** lo que está en la lista: `p`, `b`, `i`,
`em`, `strong`, `a`, `ul`, `ol`, `li`, `br`, `code`, `pre`, `blockquote`, `div` y `span`, y como
atributos solo `href` y `title` en `a`. Un `href` vale si es relativo o su esquema es `http`,
`https` o `mailto`, mirado después de quitar espacios y controles. **`//otro.pe/x` cuenta como
relativo**: no tiene esquema, y el navegador lo resuelve con el de la página hacia otro anfitrión.
No ejecuta nada, así que no es un agujero de `SEG-024`, pero es un enlace a otro dominio igual que
`https://otro.pe/x`; quien solo quiera enlaces internos tiene que filtrarlo aparte. Cualquier otra
etiqueta se quita y su texto se queda; `script`, `style`, `template`, `iframe`, `object`, `embed`,
`svg`, `math` y parecidos se quitan **con** su contenido. Todo texto y todo atributo sale escapado.

**La salida sale equilibrada.** El saneador lleva una pila de lo que abrió: lo que queda abierto al
final se cierra, un cierre que no corresponde a nada abierto se tira, y un cierre de algo que está
más abajo en la pila cierra antes lo de encima (`<b><i>x</b>` da `<b><i>x</i></b>`). Sin eso, un
`<div>` sin cerrar en un comentario deformaba el resto de la página donde se pegara. `<b/>` sale como
`<b></b>`, porque en HTML la barra no cierra una etiqueta que no es vacía. Saber si un cierre
corresponde a algo abierto se mira en un contador por etiqueta, no recorriendo la pila: con la pila,
`<b>` repetido y luego `</i>` repetido era cuadrático, y 240 KB tardaban 10 s de CPU con el GIL
tomado. Además la pila no pasa de 100 niveles; lo que se abra más adentro se quita y su texto se
queda. Hay una prueba con un mega hostil que tiene que acabar en menos de un segundo.

La primera versión era una lista de lo prohibido, y cumplía `SEG-023` y `SEG-024` al pie de la
letra: quitaba `script`, `style`, `iframe`, manejadores y `javascript:`, y conservaba lo inocuo. La
revisión encontró que aun así dejaba pasar `<form action="https://otro">` con un `<input
type=password>` (robo de contraseñas desde el origen de la aplicación), `<button formaction>`
(secuestro de un formulario existente), `id` y `name` (*DOM clobbering*), `style` con
`position:fixed` (una capa encima de la página), `href="data:..."` y `srcdoc`. Ninguna prueba del
contrato lo veía, porque el contrato enumera lo que hay que quitar, y una lista de lo prohibido
siempre se queda corta frente a lo que no se enumeró. **Esto es un hallazgo sobre el contrato, no
solo sobre esta implementación:** tal como están escritos, `SEG-023` y `SEG-024` aceptan una lista
de lo prohibido que pasa todas las pruebas y produce HTML peligroso. Deberían decir que solo
sobreviven los elementos, atributos y esquemas de una lista. Python ya es así de estricto; Java,
Rust y C++ todavía no, y el contrato no se lo exige.

`sanear_texto` devuelve texto **escapado**, sin una sola etiqueta. La primera versión devolvía el
texto con las entidades decodificadas, y eso rompía `SEG-025` de dos maneras: dos trozos inocuos
alrededor de una etiqueta quitada se juntaban en una nueva (`<<b></b>script>` daba `<script>`), y
`&lt;script&gt;` salía como `<script>`. Ahora el resultado se puede meter en HTML tal cual; quien
lo quiera como texto plano para otra cosa lo decodifica con `html.unescape`.
`sanear_nombre` se queda con lo que sigue a la última `/` o `\`,
cambia por `_` todo lo que no sea letra ASCII, dígito, `.`, `_` o `-`, quita puntos de los
extremos, antepone `_` a los nombres reservados de Windows (`CON`, `NUL.txt`), corta a 255 y nunca
devuelve vacío: devuelve `archivo`.

**Validación.** `validado(esquema, accion)` envuelve una acción:

```python
from cero.validacion import Campo, validado

esquema = {"nombre": Campo(str, maximo=50), "edad": Campo(int, requerido=False, minimo=0)}
router.agregar("POST", "/registro", validado(esquema, lambda p: p.datos["nombre"]))
```

Lo que no se entiende es 400: JSON roto, UTF-8 inválido, `NaN`, claves repetidas (dos parsers
podrían quedarse cada uno con una), números de más de 4300 cifras y anidamientos que agotan la
pila. Lo que se entiende y no vale es 422 con `{"error": ..., "campos": {"nombre": "es
obligatorio"}}`, todos los campos a la vez (`SEG-028`). Sin `Content-Type: application/json` es
415, que además obliga a un navegador ajeno a hacer preflight. Un campo que el esquema no nombra
es un error, no se tira en silencio: tirarlo sería alterar el cuerpo, y aceptarlo, asignación
masiva. `True` no es un entero aunque en Python `bool` herede de `int`. La acción recibe los datos
en `peticion.datos` y el cuerpo intacto en `peticion.cuerpo` (`SEG-027`).

### `SEG-022`: aquí sí se puede incumplir, y por eso se prueba

Rust encontró que el incumplimiento de `SEG-022` **no se podía escribir**: el limitador no recibe
la ruta, así que reintroducir el fallo no tenía dónde, y la prueba que lo citaba no podía fallar.
Lo dejó como requisito cumplido por la forma del tipo. C++ tomó la misma forma (el limitador solo
recibe el cliente) y no cuenta más.

En Python la forma del tipo no impide nada. `Limitador.pedir(cliente)` también recibe solo una
cadena, pero quien la construye es `Servidor._antes`, y nada impide escribir
`pedir(peticion.cliente + peticion.camino)`: no hay tipo que distinga «la dirección del cliente» de
«cualquier cadena». Y hay una segunda manera de incumplirlo que la firma tampoco ve: tomar como
cliente lo que devuelve `getpeername()` entero, dirección **y puerto**, con lo que cada conexión
nueva sería un cliente nuevo. Así que aquí `SEG-022` es de la clase de los que hay que probar, y
tiene dos pruebas: una por `responder` cambiando de ruta (incluido un 404) y otra por el socket con
cinco conexiones distintas. Se comprobó que fallan: con la ruta en la clave caen las dos, y con el
puerto cae la del socket.

Es el mismo patrón que `SES-011`, visto desde el otro lado: lo que en Rust es irrepresentable, en
Python es disciplina más una prueba que la vigila.

### Cómo se comprobó que las pruebas pueden fallar

Para cada `SEG-0xx` se escribió un mutante del código que incumple ese requisito y se corrieron las
pruebas que lo citan: las 28 caen, y con los arreglos de la revisión son 35 mutantes, todos
caídos. En la primera pasada sobrevivió el de `SEG-005`, y no por la
prueba: dos mutantes seguidos tenían el mismo tamaño y la misma marca de tiempo al segundo, y
Python reutilizó el `.pyc` del anterior. Con `python -B` cayó. Quien repita el ejercicio, que lo
corra sin caché de bytecode.

### Lo que no se pudo representar

Nada. Los 28 caben en la biblioteca estándar: `secrets`, `hmac`, `html.parser`, `json` y
`urllib.parse`. Queda fuera, como en el resto de implementaciones, que la clave del limitador tenga
**cardinalidad acotada**, porque `spec/seguridad.md` lo deja como prueba pendiente y no como
requisito; aquí hay una prueba de ello que no cita ningún identificador.

## Observabilidad

`spec/observabilidad.md` pide salud, un log, métricas y un log de acceso, y deja el formato libre.
Aquí el formato no es libre: las métricas van en **texto de Prometheus 0.0.4** y el acceso en el
**Common Log Format de NCSA**, dos normas que no escribió nadie de Cero, para que el banco ciego de
la fase 2 las lea sin saber en qué lenguaje está el servidor. Todo está en `cero.observabilidad`:

```python
from cero.observabilidad import Acceso, Metricas, Registro, Salud

salud = Salud(publico=False)
salud.comprobacion("bd", lambda: base.ping())
metricas = Metricas(ignoradas=["/salud", "/metricas"])
router.agregar("GET", "/salud/vida", salud.vivo)
router.agregar("GET", "/salud/lista", salud.listo)
router.agregar("GET", "/metricas", metricas.exponer)

Servidor(router, metricas=metricas,
         acceso=Acceso("acceso.log", ignoradas=["/salud"], personales=True),
         registro=Registro("app.log", nivel="AVISO"))
```

| Pieza | Por defecto | Requisitos |
|---|---|---|
| `Salud` | la monta la aplicación, en las rutas que quiera | `OBS-001`–`OBS-007` |
| `Registro` | encendido: `stderr`, nivel `INFO` | `OBS-008`–`OBS-011` |
| `Metricas` | apagadas | `OBS-013`–`OBS-018` |
| `Acceso` | apagado; `Acceso()` sin destino escribe en `stdout` | `OBS-019`–`OBS-023` |
| el estado que se anota | el que sale, en `Servidor._una` | `OBS-012` |

`Registro` y `Acceso` reciben como destino un camino de archivo, una función que recibe la línea, o
nada. Al archivo se escribe **una línea cada vez y se vacía el búfer en cada una**: el juez del banco
lee el archivo mientras el proceso sigue vivo, y una línea en el búfer no existe para él.
`cero.conforme` toma los destinos y el nivel del entorno: `CERO_CONF_ACCESO` (si no está,
`stdout`), `CERO_CONF_LOG` (si no, `stderr`), `CERO_CONF_NIVEL` y `CERO_CONF_SALUD_PUBLICA`. Por eso
su aviso de arranque pasó a `stderr`: `stdout` es del log de acceso.

**Salud.** `vivo` no recibe las comprobaciones, así que no puede fallar por ellas (`OBS-001`,
`OBS-003`); responde `{"estado": "vivo", "activo_ms": …}` con un reloj monótono. `listo` corre cada
comprobación y responde 200 o 503 con `{"estado": …, "comprobaciones": {"bd": "bien", "disco":
"mal: ConnectionError: …"}}`. En modo público el cuerpo es solo `{"estado": "listo"}` o
`{"estado": "no listo"}` (`OBS-006`, `OBS-007`). Una comprobación que devuelve algo falso está mal;
una que lanza también (`OBS-005`). Se atrapa `Exception`, no `BaseException`: un `KeyboardInterrupt`
o un `SystemExit` siguen parando el proceso.

**Cada comprobación tiene plazo.** La primera versión no lo tenía, y la revisión vio lo que eso
cuesta: con una comprobación colgada, cada sonda a `listo` retenía un hilo de los 256 del cupo, y
unas cuantas sondas después tampoco había hilo para `vivo`, que contestaba 503 por falta de cupo
aunque el proceso estuviera bien. Ahora cada comprobación corre en un hilo propio y `listo` espera
como mucho `Salud(plazo=2)` segundos por todas a la vez; la que no contesta cuenta como `"mal: sin
respuesta en el plazo de 2 s"` y la respuesta es 503. El hilo colgado no se puede matar desde
Python, así que se reutiliza: mientras siga vivo, las sondas siguientes esperan a ese mismo hilo
y no lanzan otro, y como mucho hay uno colgado por comprobación. `vivo` no toca nada de esto.

**`OBS-005` en Python.** Rust encontró que «una comprobación que lanza» era vocabulario de Java,
porque en Rust el error corriente es un valor y lanzar es `panic!`. En Python lanzar **es** el
mecanismo normal de error, como en Java, y el requisito se lee sin traducir. Lo que sí hay que
traducir es el otro lado: en Python una comprobación puede devolver `None` por olvidar el
`return`, y eso aquí cuenta como «mal», no como un fallo del endpoint. La redacción neutral que
propone Rust («una comprobación que falla de forma no prevista») cubre los tres lenguajes.

**Registro.** `registro.escribir("AVISO", "conforme", "valor={} y {}", 42, excepcion=fallo)` da
`2026-11-01T08:41:42.972+00:00 AVISO conforme valor=42 y {} | ValueError: … en app.py:12`. Los
niveles son `TRAZA`, `DEPURA`, `INFO`, `AVISO`, `ERROR` y `NADA`, el vocabulario de
`CERO_CONF_NIVEL`, y un nivel que no está en la lista (`WARN`) es un `ValueError` y no un silencio.
`interpolar` parte la plantilla por `{}` antes de sustituir, así que un valor que contenga `{}` no
se vuelve a interpolar, y un `__str__` que lanza deja `<Tipo ilegible>` en vez de llevarse la línea
(`OBS-011`). Todo carácter de control de la línea se escribe como `\xNN`: con un salto de línea
de verdad, quien controla un valor puede fabricar una línea `ERROR` falsa, y con un `ESC` puede
mandar secuencias a la terminal de quien lea el log. El servidor usa el mismo registro para las
excepciones no controladas y para la respuesta que no se puede serializar, con tipo, mensaje y el
archivo y la línea donde saltó (`OBS-010`), en una sola línea y sin la traza entera.

**Observar nunca rompe lo observado.** Si el destino falla (disco lleno, `stdout` cerrado, una
función que lanza), la línea se pierde y nada más. La primera versión dejaba subir la excepción, y
como la línea de acceso se escribe antes de mandar la respuesta, el cliente recibía la conexión
cerrada en vez de su 200. `Servidor.cerrar()` cierra los archivos del registro y del acceso.

**`OBS-012`: el estado que sale.** En Python el fallo «después de fijar 200» es una `Respuesta(200)`
con una cabecera que lleva un salto de línea: `serializar` la rechaza y lo que sale es un 500. Las
métricas y el acceso se anotan **después** de serializar y con la respuesta que de verdad se va a
mandar, así que dicen 500. El mutante que anota la respuesta de la acción cae.

**Las métricas.**

```
cero_requests_total{ruta="/tienda/{id}"} 3
cero_request_errors_total{ruta="/tienda/{id}"} 1
cero_all_requests_total 7
cero_all_request_errors_total 2
cero_request_duration_ms{ruta="/tienda/{id}",quantile="0.5"} 0.41
cero_request_duration_ms{ruta="/tienda/{id}",quantile="0.95"} 1.2
cero_request_duration_ms{ruta="/tienda/{id}",quantile="0.99"} 1.2
cero_request_duration_ms_sum{ruta="/tienda/{id}"} 2.03
cero_request_duration_ms_count{ruta="/tienda/{id}"} 3
cero_uptime_ms 1665
```

Los nombres son los de Java. La etiqueta `ruta` es el **patrón** que casó, no el camino
(`OBS-014`): `/tienda/1`, `/tienda/2` y `/tienda/3` son una serie. Lo que no casó con ningún
patrón —un 404, un 405, un `OPTIONS *`, un 429 o un 403 de CORS cortados antes del router, un
rechazo del parser— va a una sola serie, `ruta="(sin ruta)"`. Un 403 de CSRF sí cuenta en su
ruta, porque el router ya casó. Así el número de series lo fija la
aplicación al declarar sus rutas, y no quien manda peticiones. Error es todo estado 4xx o 5xx, y un
404 cuenta (`OBS-016`).

**Diferencias con Java que el banco tendrá que fijar:**

- Java pone el verbo en la etiqueta (`ruta="GET /tienda"`); aquí va solo el patrón, que es lo que
  pide el vector de `OBS-014` del diseño del banco (`ruta="/rut/a/{x}"`). `GET` y `POST` de un mismo
  patrón comparten serie.
- **Java usa el camino concreto cuando no hubo ruta** (`context.path()` en `Metrics.clave`): cada
  404 a un camino distinto crea una serie nueva. Es cardinalidad sin acotar a partir de entrada
  externa, lo mismo que `OBS-014` prohíbe, por la puerta del 404.
- Java no expone el total en Prometheus, solo en su JSON. `OBS-018` pide el total, y sumar las
  series por ruta en el mismo nombre contaría dos veces, así que va en su propia familia,
  `cero_all_requests_total` y `cero_all_request_errors_total`. El nombre está por acordar.

**Percentiles de verdad (`OBS-015`).** Java los estima con cubos fijos (1, 2, 5, 10, 25… ms), así
que un cuantil sale redondeado al tope del cubo. Rust y C++ los calculan bien, pero guardan
**todas** las duraciones de cada patrón, sin tope de memoria, y su exposición no los incluye. Aquí
cada ruta guarda sus **últimas 1024 duraciones** y el cuantil es el de rango más cercano sobre
ellas, ordenadas al exponer: con 19 peticiones de 1 ms y una de 300 ms, `0.5` da 1 ms y `0.99` da
300. `_sum` y `_count` son de toda la vida del proceso, como manda el tipo `summary`; los cuantiles,
de la ventana. La memoria es 1024 flotantes por patrón, y los patrones los fija la aplicación. La
duración va desde que se leyó la cabecera hasta que la respuesta está serializada; no incluye el
envío. El rango se calcula con enteros (`-(-95 * n // 100)`); con coma flotante no hay ningún `n`
hasta 1024 en que salga distinto para 0.5, 0.95 o 0.99, pero así no depende de ello.

**El candado de las métricas no tiene prueba que lo vigile.** Hay una que anota desde ocho hilos
mientras otros dos exponen, con `sys.setswitchinterval(1e-6)`, y comprueba que no se pierde ninguna
cuenta. Con el candado quitado **sigue pasando**: en CPython 3.12 el GIL solo cambia de hilo en los
saltos hacia atrás y en las llamadas, no entre leer y escribir `serie.peticiones += 1`, y
`setdefault` y `deque.append` son atómicos. El candado está para la compilación sin GIL (PEP 703),
donde esa suma sí se puede perder, y aquí no hay una con que probarlo. La prueba se queda, sin
citar ningún requisito: el día que corra sobre `python3.13t` sí podrá fallar.

**El log de acceso.**

```
127.0.0.1 - - [01/Nov/2026:08:41:42 +0000] "GET /texto?q=1 HTTP/1.1" 200 5
```

`%h` es la IP del cliente, `%l` siempre `-`, `%u` el usuario o `-` si no hay (`OBS-021`), la fecha en
UTC con los meses en inglés sea cual sea el *locale*, la línea de petición con su consulta
(`OBS-020`), el estado que salió y los octetos del cuerpo, o `-` si no hubo (un `HEAD`, un 204). La
aplicación identifica al usuario poniendo `peticion.usuario`; Cero no tiene autenticación. Las
comillas y las barras de la línea van escapadas, y todo octeto fuera de lo imprimible, como `\xNN`,
igual que hace Apache: una petición con `"` en el destino no parte el campo. Se anotan los errores y
las excepciones (`OBS-022`) y también los rechazos del parser cuando la línea de petición ya se
leyó (413, 417, 408 en el cuerpo); un rechazo de la propia línea o el 503 por falta de cupo no tienen
petición que anotar. La línea se escribe **antes** de mandar la respuesta: cuando el cliente la
tiene, la línea ya existe, y el juez no tiene que esperar.

Las rutas ignoradas (`OBS-017`, `OBS-023`) se declaran por prefijo y casan **por segmento completo**,
con la misma función que parte los caminos del router: ignorar `/salud` ignora `/salud/vida` y no
`/saludable`. Ignorar la raíz no se deja construir.

### Datos personales: la consulta y el usuario, detrás de una sola opción

`OBS-020` pide guardar la consulta y `OBS-021` el usuario. Con la Ley 29733 del Perú y la
minimización de datos del RGPD, las dos cosas son datos personales en un archivo que se copia, se
rota y se manda a terceros: una consulta lleva DNI, correos y tokens con facilidad. **Se espera que
el contrato cambie a «ocultas por defecto».** Mientras tanto se cumple lo que dice hoy, y las dos
van detrás de una sola opción, `Acceso(personales=True)`, que es el defecto. Con
`personales=False` la línea lleva el camino sin la consulta y `-` como usuario. Cuando el contrato
cambie, cambia el defecto y nada más.

Lo que la opción **no** cubre: `%h`, la IP del cliente, que también es dato personal. Quitarla rompe
el formato CLF y el contrato no la menciona; queda anotado para cuando se reescriban `OBS-020` y
`OBS-021`.

### Cómo se comprobó que las pruebas pueden fallar

Igual que en el hito 3: para cada `OBS-0xx` se escribió al menos un mutante que incumple el
requisito y se corrieron, con `python -B`, solo las pruebas que citan ese identificador. Son 34
mutantes para los 23 requisitos, y caen todos. Algunos de los que se probaron: `vivo` que corre las
comprobaciones (`OBS-003`), `except ArithmeticError` en vez de `Exception` (`OBS-005`, que entonces
da 500), el camino en vez del patrón y el camino solo para el 404 (`OBS-014`), la media en lugar del
cuantil (`OBS-015`), el estado de la acción en vez del enviado (`OBS-012`), el destino sin consulta
(`OBS-020`), el usuario vacío en vez de `-` (`OBS-021`), y un servidor que no anota los 5xx (`OBS-022`).

### Lo que no se pudo representar

Nada. Los 23 caben en la biblioteca estándar. El único matiz es `OBS-011`: «no lanza nunca» se
prueba con un valor, una plantilla y una excepción cuyo `__str__` y `__repr__` lanzan, pero una
prueba no puede recorrer todo lo que no lanza; un `MemoryError` sigue pudiendo salir de cualquier
línea de Python.

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

Hoy son 83: `RUT-001`–`RUT-013`, `SES-001`–`SES-013` salvo `SES-010`, `SEG-001`–`SEG-028`,
`OBS-001`–`OBS-023`, `HTTP-024`–`HTTP-026`, y
`HTTP-017`, `HTTP-019`, `HTTP-021` y `HTTP-022`, que el banco ya cubre y aquí se prueban además por
otro lado. Con los 19 de HTTP/1.1 que solo cubre el banco son 102 de los 182 del contrato. Faltan 80:
los 55 de HTTP/2, `RUT-014`–`RUT-037` (vinculación de tipos, forma de la respuesta, errores,
pipeline e inyección de dependencias) y `SES-010`. Los de sesiones y los de seguridad se cuentan solos:

```bash
grep -rhoiE --include='*.py' 'SES[-_][0-9]{3}' pruebas | tr a-z_ A-Z- | sort -u | wc -l   # 12
grep -rhoiE --include='*.py' 'SEG[-_][0-9]{3}' pruebas | tr a-z_ A-Z- | sort -u | wc -l   # 28
grep -rhoiE --include='*.py' 'OBS[-_][0-9]{3}' pruebas | tr a-z_ A-Z- | sort -u | wc -l   # 23
```

`SES-010` no sale porque ninguna prueba lo cita: sin h2 no hay prueba que pueda fallar.
