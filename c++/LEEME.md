# Cero en C++

Tercera implementación del contrato de [`spec/`](../spec). Como la de Rust, no es una traducción
del código Java: la referencia son los requisitos numerados, y el juez son los mismos vectores de
conformidad, que son bytes sobre un socket y no saben en qué lenguaje está escrito quien responde.

**Estado: hito 13, y el contrato está cubierto entero.** El framework sobre HTTP/1.1 y HTTP/2 en
claro (h2c) por prefacio y por `Upgrade: h2c` en el mismo puerto, con control de flujo de salida,
las defensas de HTTP/2 Bomb, middleware, manejo de fallos, contenedor de dependencias, validación y
sesiones en disco. **23 de 23 vectores del banco**, **11 de 11 en los ataques**, **los 32 del
apéndice C del RFC 7541**, **145 de 146 en h2spec** —el que falta es el mismo que en Java y en
Rust— y **175 de los 175 requisitos aplicables** citados por una prueba.

| Hito | Qué cubre | Estado |
|---|---|---|
| 1 | HTTP/1.1 y ruteo | 23 de 23 vectores · `HTTP-001`–`HTTP-023`, `RUT-001`–`RUT-013` |
| 2 | Sesiones | `SES-001`–`SES-010` · 11 pruebas |
| 3 | Seguridad transversal | `SEG-001`–`SEG-026` menos el token · 15 pruebas |
| 4 | Observabilidad | `OBS-001`–`OBS-022` menos dos del pipeline · 15 pruebas |
| 5 | El framework montado | pipeline completo · 15 pruebas |
| 6 | JSON, formularios y estáticos | lo que hacía falta para usarlo · 12 pruebas |
| 7 | HTTP/2 · capa de tramas | `H2-001`–`H2-014` y `H2-027` · 15 pruebas |
| 8 | HTTP/2 · HPACK | `H2-015`, `H2-016`, `H2-033`, `H2-039` · 20 pruebas con los vectores del RFC |
| 9 | HTTP/2 · flujos y h2c | la máquina de estados y el conductor · h2spec 138 de 146 · 50 pruebas |
| 10 | HTTP/2 · control de flujo de salida | `H2-040`, `H2-041` · h2spec 145 de 146 · 13 pruebas, y 6 de robustez |
| 11 | Plazos, topes y HTTP/2 Bomb | `H2-050`–`H2-055`, `HTTP-024`–`HTTP-026` · 11 de 11 en `ataques.py` · 11 pruebas |
| 12 | Ruteo, fallos, dependencias y validación | `RUT-014`–`RUT-031`, `RUT-035`, `SEG-027`, `SEG-028` · 24 pruebas |
| 13 | Sesiones en disco, y `cero-data` | `SES-012` entre almacenes que no comparten memoria · **175 de 175** · 8 pruebas |

## Cuántos requisitos citan las pruebas

La cuenta que hace la integración continua, desde `c++/`: **175** requisitos distintos, que son
todos los aplicables.

El contrato tiene 182. Siete se retiran por no poderse observar desde fuera, y aquí no se citan:
`RUT-032`, `RUT-033`, `RUT-034`, `RUT-036` y `RUT-037` hablan de la forma del API del contenedor
de dependencias; `SES-011`, de que consultar la cookie pendiente la marque como emitida; `SES-013`,
del nombre de una tabla. `RUT-035` —detectar un ciclo— sí se queda: un ciclo que cuelga el
proceso se ve desde fuera. La prueba que antes citaba `SES-011` sigue, y ahora cita `SES-008`, que
es lo que se ve: la cookie sale una vez.

```bash
grep -rhoiE '(HTTP|OBS|RUT|SEG|SES|H2)[-_][0-9]{3}' pruebas | tr a-z_ A-Z- | sort -u | wc -l
```

## Usarlo

```bash
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release && cmake --build build -j
ctest --test-dir build                       # las pruebas propias
./build/conforme 8777                        # y el servidor del banco
python3 ../spec/banco/correr.py 127.0.0.1 8777
python3 ../spec/banco/ataques.py ./build/conforme   # los ataques, que arrancan su propio servidor
```

## La decisión de fondo: C++ no tiene sockets

Java los trae en el JDK. Rust los trae en `std::net`. El estándar de C++ no trae ninguno, porque
el *Networking TS* se abandonó. Quedaban dos salidas: traer asio —una dependencia, y este proyecto
no admite ninguna— o llamar a POSIX. Se llama a POSIX.

Es el mismo tipo de decisión que en Rust fue no usar un runtime asíncrono: el contrato **no habla
de cómo se abre el socket**, así que se cumple igual. Lo que cambia es que aquí no hay nada
portable debajo, y eso hay que decirlo en vez de disimularlo. Como en Rust, un hilo del sistema por
conexión: Java tiene hilos virtuales en la plataforma y aquí no hay equivalente sin dependencias.

TLS, igual que en Rust: **fuera del proceso**. El argumento está en
[`rust/LEEME.md`](../rust/LEEME.md) y no cambia porque cambie el lenguaje — escribir una pila TLS
cuya corrección no se puede comprobar desde fuera sería la clase de afirmación sin medir que el
resto del proyecto se niega a hacer.

## Qué se trae de la implementación en Rust

Dos cosas aprendidas por las malas allí, aplicadas desde el primer día:

**Una prueba por requisito, en `pruebas/`.** En Rust los 35 requisitos de ruteo figuraron cubiertos
durante once hitos por estar citados en `src/`, junto al código que los implementa, que es justo
donde citarlos no demuestra nada. La cuenta solo mira `pruebas/`.

**El pipeline se puede llamar sin socket.** `Servidor::responder` toma una petición y devuelve la
respuesta. Es lo que hace probable el ruteo sin abrir un puerto, y de paso lo que permite montar
Cero dentro de otra cosa.

## Las tablas de HPACK son estáticas y se comprueban

Son datos fijos en el código, tomados de los apéndices A y B del RFC; no hay ningún guion que las
genere. Lo que las respalda es la **suma de Kraft** —la de dos elevado a menos cada longitud—, que en un código
prefijo completo vale exactamente 1, y hay una prueba que la calcula: una tabla de 257 filas
copiada a mano no revienta cuando se equivoca, decodifica mal y en silencio.

Y los vectores del apéndice C son la única comprobación de todo el proyecto que no se puede
escribir «de acuerdo con lo que hace el código»: los octetos vienen dados y **el estado de la tabla
dinámica después de cada paso también**. Un decodificador que acierte el resultado y deje la tabla
distinta pasaría cualquier prueba propia y fallaría en la petición siguiente.

## Las cabeceras se declaran a mano, y eso lo decidió el CI

Dos veces falló en GitHub lo que aquí compilaba: `<print>` no existe hasta GCC 14 y `std::llround`
no venía sin `<cmath>`. La causa de la segunda es que libc++ arrastra cabeceras que libstdc++ no,
así que un archivo que *parecía* completo solo lo estaba en una implementación.

Ahora **cada archivo incluye lo que usa**, sin heredarlo de otro. Es más líneas de `#include` y a
cambio el proyecto compila igual en los dos sitios, que es la mitad del argumento de no tener
dependencias: si el framework solo construye con un compilador, el compilador es la dependencia.

## Lo que el lenguaje pone fácil, y lo que no

`std::expected` de C++23 es exactamente el `Result` de Rust, así que el parser se lee igual en los
dos: cada rechazo cita el requisito que lo exige y viaja con el estado que el RFC le asigna, en vez
de decidirse en el sitio de la llamada.

Lo que no pone fácil es el corredor de pruebas: no hay ninguno en la biblioteca estándar. Catch2 o
GoogleTest serían la primera dependencia del proyecto, así que hay uno propio en
[`pruebas/prueba.hpp`](pruebas/prueba.hpp) — cuarenta líneas, y Java tiene el suyo por este mismo
motivo.

## Los dos fallos que en Rust solo aparecieron al montarlo

Aquí están comprobados desde el principio, porque ninguna prueba de módulo puede verlos.

**El CSRF tapaba el 405.** Corría antes del ruteo, así que un verbo no admitido recibía 403 en vez
de 405: la respuesta atribuía el fallo a la causa equivocada, que es lo que `RUT-009` prohíbe al
exigir distinguir 404 de 405. Ahora el ruteo va primero y el CSRF después, ya sabiendo que la
petición iba a alguna parte.

**La sesión creada dentro de la acción no emitía su cookie.** `abrir_sesion()` la creaba, pero el
punto que emite la cookie solo miraba la que había llegado **con** la petición. El cliente abría
sesión y no recibía nada. Es `SES-010` con otra cara, y por eso emitir la cookie, guardar la sesión
y anotar las métricas pasan por un único sitio.

## Pieza por pieza: lo que el código no dice solo

El código no lleva comentarios, y el requisito que comprueba una prueba va en su nombre
—`PRUEBA(rut_029_…)`— o en el mensaje de su `COMPRUEBA`. Se cuentan así, sin distinguir mayúsculas
y juntando guion y guion bajo:

```bash
grep -rhoiE '(HTTP|OBS|RUT|SEG|SES|H2)[-_][0-9]{3}' pruebas | tr a-z_ A-Z- | sort -u | wc -l
```

Lo que explicaban los comentarios, y no está ya contado arriba:

- **`peticion.hpp`.** El parser lee de un `Lector`, que en producción es el socket y en las pruebas
  una cadena: por eso los 23 vectores se corren sin abrir un puerto. El estado de un rechazo lo fija
  el RFC y viaja con el motivo. `Content-Length` con `Transfer-Encoding` se rechaza: son dos
  longitudes del mismo cuerpo, y es la puerta del contrabando.
- **`servidor.cpp`.** `DesdeSocket` lee sobre un búfer propio que **dura lo que la conexión**: uno
  nuevo por petición tira la siguiente de un cliente que las encadena. `Contexto` es lo que recibe
  la acción; el contrato no dice cómo se llama, solo qué tiene que estar. El log de acceso anota el
  estado que sale (`OBS-012`).
- **`ruta.hpp`.** Las rutas se registran en vez de leerse de anotaciones, y los requisitos se
  cumplen igual. `VerboNoPermitido` existe porque el camino que existe con otro verbo no es un 404
  (`RUT-009`) y hay que poder enumerar los que valen (`RUT-010`). La barra final se normaliza a mano
  y no con `views::split`, que necesita un constructor que libstdc++ no tiene hasta GCC 14.
- **`seguridad.hpp`.** El contrato fija el comportamiento por defecto, no la política. El preflight
  ajeno se rechaza (`SEG-013`) y la petición simple no (`SEG-010`). El limitador usa solo el
  cliente como clave (`SEG-022`). El token CSRF se compara en tiempo constante: con `==` el tiempo
  depende del prefijo común. El saneador corta la etiqueta donde empieza un manejador de evento o
  un `javascript:` en vez de borrarla entera (`SEG-024`).
- **`observabilidad.hpp`.** `vivo()` no admite comprobaciones (`OBS-001`). Un error se registra con su
  tipo (`OBS-010`), que se pasa a mano porque el nombre de un tipo en ejecución viene decorado y
  deshacerlo no es portable. Las métricas van por patrón y no por URL (`OBS-014`).
- **`sesion.hpp`.** Reloj de pared y no `steady_clock`, porque una sesión en una tabla la leen dos
  instancias. El candado va dentro de cada sesión y no alrededor del almacén (`SES-007`).
  `cookie_pendiente()` no es `const`: consultarla la marca como emitida. El identificador
  sale de la fuente del sistema operativo (`SES-002`). `Sesiones` es una interfaz, y hay dos: el
  `Almacen` en memoria y `AlmacenEnDisco` en `cero-data`. `Caducidad` es la regla que comparten, y
  `Sesion::nueva` y `renovar_id` lo que los dos necesitan para crear y rotar.
- **`fallo.hpp` y `servidor.hpp`.** Una acción devuelve `Respuesta`, texto, `Json` o nada, y
  `accion` lo convierte (`RUT-019`, `RUT-020`); devolver nada es 204. Para fallar **lanza**: es lo
  que C++ tiene, y `Fallo` es el tipo hecho para eso. Su estado va de 400 a 599: construir uno
  fuera de ese rango lanza `invalid_argument`, que acaba en 500, porque un «fallo» con 200 es un
  error del programa. Un `Fallo` 4xx conserva estado y mensaje, y su `detalle` sale como cuerpo
  JSON (`RUT-026`); un 5xx conserva el estado, pero ni el mensaje ni el `detalle` salen: van al
  log. Cualquier otra excepción —estándar o no— es un 500. En los dos casos el cliente recibe
  `{"error":"error interno","ruta":"/patrón"}` (`RUT-024`, `RUT-025`).
  Antes de este hito una excepción en una acción subía hasta el hilo de la conexión y, sin nadie que
  la atrapara, llamaba a `std::terminate`: una petición tumbaba el servidor. `al_fallar` decide antes
  que la regla por defecto, incluso sobre un 500 (`RUT-027`); si también lanza, se anotan los dos
  fallos —el original y el suyo— y se aplica la regla.
  `usar` compone el middleware por índice, en el orden en que se declaró (`RUT-028`), y corre
  también sin ruta (`RUT-029`); `por_defecto` atiende lo no enrutado sin tocar el 405 (`RUT-030`,
  `RUT-031`). Lo que lance un middleware se atrapa en `responder`, y `responder` entero tiene un
  último `catch` que da 500 con las cabeceras de protección: el almacén de sesiones también puede
  lanzar. El hilo de cada conexión y el de cada flujo h2 tienen además el suyo; en h2 el flujo se
  corta con `RST_STREAM INTERNAL_ERROR` y la conexión sigue. Nada llega a `std::terminate`.
  `variable_como<T>` acepta los enteros —no `bool` ni los de carácter, y eso se comprueba al
  compilar—, porque es lo que `from_chars` da igual en libc++ y en libstdc++, y exige consumir el
  segmento entero: `7x` no es 7 (`RUT-015`). `cuerpo_como<T>` pide a `T` un `de_json`: no hay
  reflexión, y la forma de leerse es cosa del tipo. JSON mal formado es 400; bien formado y con
  otra forma, 422 (`RUT-017`).
- **`dependencias.hpp`.** Cada servicio se registra con la función que lo construye, se guarda por
  `type_index` y se construye **una vez**, aunque lo pidan varios hilos a la vez: cada tipo tiene su
  obra con su candado, y el segundo espera al primero. Registrar y leer las recetas pasa por el
  candado del contenedor, así que se puede registrar con el servidor en marcha. Los ciclos se
  detectan con una pila por hilo, que lleva también qué contenedor resuelve, y antes de tomar el
  candado de la obra (`RUT-035`). Un ciclo repartido entre dos hilos —A pide B en uno mientras B pide
  A en otro— se bloquea en vez de detectarse; en un solo hilo se detecta siempre.
  No va dentro del `Contexto`: una acción que lo necesite lo captura.
- **`validacion.hpp`.** 422 y no 400 (`SEG-028`), con el motivo de cada campo; se para en la primera
  regla que falla de cada campo, y el cuerpo vuelve tal cual (`SEG-027`). `Texto` cuenta puntos de
  código y no octetos, y un texto que no es UTF-8 válido —secuencia cortada, forma larga, sustituto,
  más allá de U+10FFFF— es 422 «no es UTF-8 válido»: contando solo los octetos de cabecera, un mega
  de bytes de continuación sueltos medía cero caracteres y pasaba el máximo. `Obligatorio` rechaza
  lo ausente, `null`, `""`, `[]` y `{}`; el tipo lo dicen `Texto` y `Entero`, que van detrás. `Json::entero` ya no convierte un número que no cabe:
  con 2^63 el `static_cast` era comportamiento indefinido, y en ARM devolvía un entero falso.
- **`cero-data/disco.hpp`.** `SES-012` pide que una sesión abierta en una instancia se reconozca en
  otra. Sin dependencias no hay base de datos, pero hay sistema de archivos: un archivo por sesión
  en una carpeta que configura la aplicación. Lo que la respalda es una prueba con dos servidores
  que no comparten nada salvo la carpeta. El identificador llega de la cookie, así que se exige
  hexadecimal de 64 antes de tocar el disco: si no, `../` lee sesiones de otra carpeta. La carpeta es
  0700 y cada archivo 0600. Se escribe en un provisional con `O_EXCL` y se renombra, que en POSIX es
  atómico: quien lee ve la versión vieja o la nueva, nunca media. Como en Rust: se guarda solo si
  cambió, rotar escribe el nuevo antes de borrar el viejo, y la caducada se borra al tocarla.
  **`SES-007` dentro del proceso:** mientras una petición tiene una sesión, las demás del mismo
  proceso reciben la misma `Sesion` y el mismo candado, no una copia leída del disco; hay un registro
  por identificador, con `weak_ptr`, que se vacía solo cuando nadie la usa. Cada `guardar` escribe
  el estado entero bajo el candado de la sesión, así que lo que puso una petición no lo borra la
  otra. **Entre procesos no hay candado:** dos instancias que escriben a la vez la misma sesión se
  pisan y gana la última. Leer del disco ocurre bajo el candado del registro, así que dos peticiones
  no pueden abrir dos copias de la misma. Se hace `fsync` antes de renombrar, y los provisionales
  que dejó una caída, de más de una hora, se barren al arrancar. La carpeta no puede ser un enlace ni
  de otro usuario. Una fecha que no cabe en el reloj invalida el archivo en vez de desbordarlo. Una
  carpeta en red (NFS) sirve para varias máquinas solo si su `rename` es atómico.
- **`estaticos.cpp`.** Tras resolver enlaces se comprueba que el camino sigue dentro de la raíz.
- **`json.hpp`.** Propio, porque una biblioteca de JSON sería una dependencia escondida.
- **`formato.hpp`.** `formato(...)` y `linea(...)` sobre `<format>`, porque `std::print` pide GCC 14.
- **`http2/trama.hpp`.** Casi todo son rechazos. `FalloConexion` se lleva la conexión y `Cortado` solo
  un flujo, y la distinción importa. `Fuente` separa el parser del socket. Hay dos juegos de
  `Ajustes` por conexión, y los del cliente no pisan los nuestros. Los ajustes de una trama se
  acumulan en orden (§6.5.3).
- **`http2/hpack.hpp`.** La tabla dinámica es estado compartido con el cliente y vive lo que la
  conexión; un bloque que no se decodifica rompe la conexión (`H2-015`, `H2-016`) y los trailers se
  decodifican aunque se tiren. El índice 1 es la entrada más reciente; una que no cabe vacía la
  tabla (§4.4); el entero de longitud variable tiene tope de cinco septetos; el codificador no
  indexa. Un bloque que se expande a más de 256 campos (`H2-050`) o a más de lo anunciado en
  `SETTINGS_MAX_HEADER_LIST_SIZE` (`H2-051`) corta la conexión con `ENHANCE_YOUR_CALM`; se cuenta
  campo a campo según salen, cada `cookie` por separado, y no al terminar.
- **`http2/flujo.hpp`.** Convierte tramas en peticiones, y `Sesion` vive lo que el socket: la tabla
  de HPACK y los identificadores no se reinician a mitad. Terminar de responder cierra **nuestra**
  mitad y no el flujo: el cliente puede seguir mandando cuerpo hasta `END_STREAM`, y hasta entonces
  hay que validarlo (es el fallo que la 0.8.0 arregló en Java: el `content-length` dejaba de
  comprobarse para lo que llegara después). Un flujo malo no es una conexión mala: las peticiones
  malformadas (`H2-018` a `H2-026`, `H2-043`) cortan el flujo y la conexión sigue. Entre HEADERS y su
  última CONTINUATION no cabe ninguna trama (§6.2). Un bloque se decodifica **siempre**, aunque el
  flujo vaya a cortarse —rechazado por el tope, cerrado o dependiente de sí mismo—: saltárselo
  descoloca HPACK para todo lo que venga después (`H2-035`). PRIORITY se descarta, pero un flujo que
  depende de sí mismo se corta (`H2-044`). El tope de flujos cuenta todos los vivos, incluido el que
  ya dijo `END_STREAM` y espera respuesta (`H2-046`), y el hueco de un flujo con trabajo en marcha no
  se libera al cortarlo sino cuando su hilo termina: si no, repetir HEADERS con `END_STREAM` y un DATA
  sobre el mismo flujo lanzaba hilos sin tope hasta tumbar el proceso. Cortar un flujo con trabajo
  en marcha cuenta como anulado (`H2-037`), y todo RST que mandamos cuenta contra un tope propio,
  porque un DATA sobre un flujo cerrado es un RST gratis. Todo corte pasa por `cortar`, que es lo
  único que saca un flujo de la tabla. La ventana de la conexión empieza en 65 535 sea
  cual sea la inicial anunciada, se descuenta con el relleno incluido y se devuelve también por lo
  que llega a un flujo cerrado; si no, la conexión se queda sin crédito poco a poco. El
  `content-length` se compara con los datos sin relleno, y también al llegar los trailers. Los
  topes de inundación (`H2-036` a `H2-038`) no son ajustes del protocolo sino política: holgados para
  un cliente normal y finitos para uno anormal, porque cada trama de una inundación es válida.
- **`http2/conexion.hpp`.** El conductor: el hilo de la conexión lee y un hilo por flujo atiende
  (`H2-029`), igual que en Rust; atenderlos en fila sobre el que lee sería HTTP/1.1 con más ceremonia.
  La salida va con candado y una trama sale entera o no sale; un bloque de cabeceras que no cabe en
  una trama sigue en CONTINUATION y sale de una vez, para que nada se meta por medio. El hilo que
  responde avisa al que lee, que es quien da el flujo por cerrado. Si el sistema no puede crear el
  hilo, el flujo se rechaza con REFUSED_STREAM en vez de dejar escapar la excepción. La petición
  ascendida por h2c llega como `HTTP/2` y sin las cabeceras del ascenso. `DesdeSocket` es a la vez el
  `Lector` de HTTP/1.1 y la `Fuente` de h2, así que el preámbulo se ojea sin consumirlo y lo que ya
  estuviera en el búfer tras un `101` no se pierde. Un preámbulo falso es una petición HTTP/1.1 con
  un método que no existe y recibe 501 (`H2-001`). Un método que no se implementa recibe 501 en su
  flujo (`H2-018`). `:authority` pasa a `host`, y varias `cookie` se juntan con `; `. Al cerrar, media
  conexión y un vaciado: cerrar con octetos sin leer manda un RST que borra el GOAWAY (`H2-048`).
  Los escritos no levantan `SIGPIPE`.
  Cada DATA pide antes su sitio a `Ventanas`, que lleva la ventana de la conexión y la de cada flujo
  (`H2-040`). Quien espera sitio espera ahí y no con la salida tomada, así que un flujo parado no tapa
  a los demás ni al hilo que lee. Un `WINDOW_UPDATE` que sube una ventana por encima de 2^31-1 corta
  el flujo, o la conexión si es la de la conexión, y unos ajustes que lo hacen cortan la conexión
  (`H2-041`). Cambiar la ventana inicial mueve la de los flujos abiertos, aunque quede en negativo.
  El hueco de un flujo se suelta bajo el mismo candado con que sale su última trama: si se soltara
  después, un cliente que abre otro en cuanto ve el `END_STREAM` choca con el tope sin haberlo
  pasado. El `REFUSED_STREAM` que mandamos nosotros no cuenta contra el cliente, y los
  `WINDOW_UPDATE` de la conexión solo cuentan como control sin flujo cuando no hay ninguno vivo: si
  no, una descarga larga acabaría en `ENHANCE_YOUR_CALM`. Una escritura que no avanza tiene plazo;
  al vencer, la salida se da por rota y el socket se cierra en los dos sentidos, para que el hilo que
  lee también se entere. Esperar ventana también tiene plazo: cada trama de hasta 16 KiB tiene
  `escritura` para salir entera, y si el crédito no llega a tiempo el flujo se corta con
  `RST_STREAM CANCEL` (`H2-052`); el plazo se fija por trama y no por octeto, porque un plazo que
  reinicia cada `WINDOW_UPDATE` es justo lo que el goteo explota. Una conexión sin `HEADERS` ni `DATA`
  durante `inactividad` recibe GOAWAY `NO_ERROR` y se cierra: `PING`, `SETTINGS` o `WINDOW_UPDATE`
  no cuentan como trabajo (`H2-053`). Mientras algún flujo tenga su respuesta en marcha —calculándose
  o esperando ventana— no hay inactividad. Lo construido y sin mandar se suma por conexión, y pasar
  de 32 MiB —salvo si no hay nada más en vuelo— manda GOAWAY `ENHANCE_YOUR_CALM`, cierra nuestra mitad y despierta a quien esperaba
  ventana (`H2-054`); el hilo que lee se entera en la siguiente trama o cuando el cliente cierra.
- **`socket.hpp`.** Lo que comparten HTTP/1.1 y h2 al escribir: sin `SIGPIPE` —un cliente que corta
  a mitad de respuesta mataba el proceso entero— y con `Plazos`, que son ajustes del `Servidor`
  (`.plazos(...)`): `sin_avance` (30 s sin mover un octeto), `escritura` (60 s por cada 16 KiB) e
  `inactividad` (120 s sin una petición nueva, en HTTP/1.1, y sin `HEADERS` ni `DATA`, en h2). Un
  plazo de 0 o negativo no arranca: `escuchar` devuelve 1 sin abrir el puerto (`HTTP-025`), porque 0
  en un socket quiere decir «sin plazo» y en una cuenta atrás «ya pasó». `Limites`
  (`.limites(...)`) lleva el presupuesto de respuestas en vuelo (32 MiB) y el tope del cuerpo
  (10 MiB): un cuerpo mayor es 413, en HTTP/1.1 antes de leerlo (`HTTP-026`; antes se reservaba lo
  que dijera `Content-Length`) y en h2 con `RST_STREAM NO_ERROR` (`H2-055`), en cuanto llega un
  `content-length` de más o en cuanto lo acumulado pasa del tope, siempre antes de la acción. El plazo de `SO_SNDTIMEO` vuelve a empezar cada vez que
  el cliente lee algo, así que solo no basta: un cliente que lee un octeto antes de que venza retiene
  la conexión sin fin. Por eso cada 16 KiB tiene su plazo total, y se escribe en piezas de 4 KiB
  para que ninguna llamada a `send` pueda durar más que eso. En macOS el plazo de `send` se reinicia
  dentro de la misma llamada con cada avance; un `send` grande no vencería nunca. Si no se puede
  crear el hilo de una conexión, se cierra esa conexión y el servidor sigue.
- **`Servidor::direccion`.** Por defecto se escucha en `0.0.0.0`; el servidor del banco y las
  pruebas escuchan en `127.0.0.1`, porque en macOS `SO_REUSEADDR` deja que un socket comodín
  comparta puerto con otro proceso que escucha en la dirección concreta. Admite lo mismo que
  Rust: un literal IPv4 o IPv6 (`::1`) o un nombre (`localhost`), resuelto con `getaddrinfo`, y
  escucha en la primera dirección que se deja. Si no vale o el puerto está ocupado, `escuchar`
  lo dice en stderr y devuelve 1.
- **`conforme.cpp`.** El servidor del banco lee el cuerpo en `/eco`: sin eso los vectores de
  encuadre no comprueban nada. `/grande` da 1 MiB para `H2-054`, y un segundo argumento fija los
  plazos en milisegundos —el de inactividad, el doble—, que es como lo arranca `ataques.py`.
- **`CMakeLists.txt`.** CMake es a C++ lo que Maven a Java y Cargo a Rust: construye, no se enlaza.
  Lo que se enlaza es la biblioteca estándar y nada más.
- **`pruebas/prueba.hpp`.** `COMPRUEBA` es variádico y no de dos argumentos: con dos, una condición
  con llaves dentro —`T{a, b}`— parte el macro por la coma y el error no habla de eso.

## Lo que el contrato deja a cada implementación

El contrato fija algunos números —256 campos por bloque, 32 MiB en vuelo, 10 MiB de cuerpo— y
deja otros a lo «configurado». Las tres implementaciones no eligieron lo mismo en estos, y conviene
saberlo antes de desplegar: una conexión keep-alive ociosa se cierra a los 30 s en Java y a los
120 s en Rust y en C++.

| Valor por defecto | Java | Rust | C++ | Requisito |
|---|---|---|---|---|
| Inactividad: keep-alive y h2 sin trabajo | 30 s (`idleTimeoutMillis`) | 120 s (`Plazos::inactividad`) | 120 s (`Plazos::inactividad`) | `HTTP-024`, `H2-053` |
| `SETTINGS_MAX_HEADER_LIST_SIZE` anunciado | 32 KiB (`http2MaxListaCabeceras`) | 16 KiB | 16 KiB | `H2-051` |
| Flujos anulados por conexión antes de `ENHANCE_YOUR_CALM` | 100 (`http2MaxAnulados`) | 200 | 200 | `H2-037` |

## Lo que falta

Del contrato, nada. Los siete requisitos retirados están arriba.

El único fallo que queda en h2spec es el `3.5.2`, que no aplica en un puerto compartido
(`spec/README.md`), igual que en Java y en Rust.
