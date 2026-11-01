# Cero en Rust

Segunda implementación del contrato de [`spec/`](../spec). No es una traducción del código Java:
la referencia son los requisitos numerados, y el juez son los mismos vectores de conformidad, que
son bytes sobre un socket y no saben en qué lenguaje está escrito quien responde.

**Estado: hito 12, y el contrato está cubierto entero.** HTTP/2 salvo TLS, ruteo con middleware,
manejo de fallos, contenedor de dependencias, validación y sesiones en tabla. **h2spec da 145 de
146**, el mismo número que Java y con el mismo único fallo, el `3.5.2`, que asume un puerto
dedicado a h2c y no aplica aquí.

| Hito | Qué cubre | Estado |
|---|---|---|
| 1 | HTTP/1.1 y ruteo | 23 de 23 vectores |
| 2 | Sesiones | 13 de 13 requisitos · 13 pruebas |
| 3 | Seguridad transversal | 28 de 28 requisitos · 18 pruebas |
| 4 | Observabilidad | 23 de 23 requisitos · 16 pruebas |
| 5 | El framework montado | pipeline completo · aplicación de ejemplo |
| 6 | JSON, formularios y estáticos | lo que hacía falta para usarlo de verdad |
| 7 | HTTP/2 · capa de tramas | 11 de los 49 requisitos · 13 pruebas |
| 8 | HTTP/2 · HPACK | 5 requisitos más · 20 pruebas, con los vectores del RFC 7541 |
| 9 | HTTP/2 · flujos y h2c | la máquina de estados y el conductor · 25 pruebas |
| 10 | HTTP/2 · control de flujo de salida | h2spec de 130 a 145 · 18 pruebas |
| 11 | Ruteo, fallos, dependencias y validación | 172 de los 173 requisitos · 36 pruebas |
| 12 | Sesiones en tabla, y `cero-data` sabe escribir | **173 de 173** · 9 pruebas |

**Los 173 requisitos del contrato**: los 23 de HTTP/1.1 por los vectores del banco y el resto por
una prueba que los cita. Se cuentan así:

```bash
grep -rhoiE '(HTTP|OBS|RUT|SEG|SES|H2)[-_][0-9]{3}' cero-http/tests cero-data/tests \
  | tr a-z_ A-Z- | sort -u | wc -l
```

Sin distinguir mayúsculas y con guion o guion bajo, porque desde que el código no lleva
comentarios el requisito va en el nombre de la prueba —`fn rut_014_comodin_no_cruza_barra()`— o en
el mensaje de su aserción. El `tr` junta `rut_014` y `RUT-014` en uno; sin él, cada requisito
contaría dos veces.

**En las pruebas, no en el código.** La cuenta de antes miraba también los comentarios de `src/`, y
eso hacía que los 35 requisitos de ruteo figuraran como cubiertos **sin una sola prueba que los
comprobara**: estaban citados donde se implementaban, que es justo donde citarlos no demuestra
nada. Al mirar solo `tests/` la cifra honesta era 120, no 155.

Queda fuera **ALPN sobre TLS**, que no es un requisito del contrato y no se va a hacer: más abajo
se explica por qué.

## El hito 12: las sesiones fuera del proceso

`SES-013` —nombre configurable de la tabla— presuponía un almacén persistente, y para tenerlo
había que arreglar antes dos cosas que no se veían:

**`cero-data` no sabía escribir.** Tenía `por_clave`, `todos` y `borrar`, y ningún `guardar`. Un
módulo de acceso a datos que lee y borra no es un módulo de acceso a datos, y la implementación en
memoria solo entendía `SELECT` y `DELETE`, así que tampoco había con qué probarlo. `Repositorio`
guarda ahora con `UPDATE` y, si no tocó nada, `INSERT`: dos órdenes y no un `UPSERT`, porque cada
motor lo escribe distinto —`ON CONFLICT`, `ON DUPLICATE KEY`, `INSERT OR REPLACE`— y pedirle uno
al driver sería meter el dialecto de un motor dentro del contrato.

**Una sesión medía su edad con `Instant`,** que es monótono y local al proceso: no se puede guardar
ni comparar con el de otra instancia. Ahora usa el reloj de pared, y las fechas se guardan como
enteros porque el tipo temporal de cada motor es distinto.

Con eso, `Sesiones` pasa a ser un rasgo y hay dos implementaciones: el almacén en memoria en
`cero-http` y `AlmacenSql` en `cero-data`, que es donde vive `JdbcSessions` en Java por la misma
razón —la capa HTTP define el contrato y quien sabe de bases de datos lo implementa—. Tres
decisiones que no son evidentes:

- **Se guarda en un solo punto**, el mismo que emite la cookie. `SES-010` nació de tener dos
  salidas y hacer el trabajo en una.
- **Solo si cambió.** Escribir en cada respuesta serían dos viajes a la base por cada `GET` que no
  tocó nada, y eso convierte el almacén compartido en el cuello de botella del servidor.
- **Rotar escribe la fila nueva antes de borrar la vieja.** Al revés, un fallo entre los dos pasos
  pierde la sesión; así lo peor que deja es una fila de sobra que caduca sola.

Y una caducada se borra al tocarla, no en un barrido: no hace falta un hilo que limpie, y la fila
muerta no puede volver a autenticar a nadie por mucho que quede.

## El hito 11: lo que el ruteo no tenía

El bloque de ruteo estaba a medias y no se notaba, porque los 35 requisitos aparecían citados en
`src/`. Faltaba código, no solo pruebas:

- **Los fallos.** Una acción devolvía `Respuesta` y no podía fallar, así que `RUT-024` a `RUT-027`
  no tenían dónde ocurrir. Ahora devuelve `Respuesta` **o** `Result`, y las dos valen: el rasgo
  `EnRespuesta` existe para que una acción que no falla no tenga que decir que no falla. Sin eso,
  añadir fallos obligaba a envolver en `Ok(...)` hasta la acción más tonta, y un framework que
  cobra ceremonia por una función que nunca falla acaba teniendo acciones que se tragan sus errores
  para no pagarla.
- **El middleware** (`RUT-028` a `RUT-031`). Lo que había era un pipeline fijo. Se compone por
  índice y no apilando clausuras: componer `Box<dyn Fn>` en Rust obliga a nombrar un tipo que se
  anida consigo mismo, y lo que se gana es ilegible.
- **El contenedor de dependencias** (`RUT-032` a `RUT-036`), que no existía. Java lo resuelve por
  reflexión; aquí cada servicio se registra con la función que lo construye. Es la segunda vez que
  `spec/` demuestra no llevar dentro una decisión de Java, después del ruteo. El ciclo se detecta
  con una pila por hilo, y la fábrica corre **fuera** del candado: construir dentro sería un
  candado no reentrante bloqueándose contra sí mismo en la segunda cadena de dos eslabones. Java
  tropezó con la misma piedra con otra forma, `computeIfAbsent` recursivo sobre el mismo mapa.
- **La validación** (`SEG-027` y `SEG-028`), que tampoco existía: 422 y no 400, porque el cuerpo se
  entendió y lo que falla es su contenido.

Y un agujero de verdad: **el token CSRF no se emitía nunca** (`SEG-017`). El servidor sabía
validarlo y no sabía darlo, así que una ruta protegida por CSRF era imposible de usar — ningún
cliente tenía de dónde sacar el token—. Además solo se aceptaba por cabecera, lo que dejaba fuera a
un formulario HTML, que es donde el CSRF hace más falta.

Para poder probar todo esto, el pipeline entero se puede llamar **sin socket**: `Servidor::responder`
toma una petición y devuelve la respuesta. Es el mismo argumento que hace probable `Sesion` en
HTTP/2, y de paso es lo que permite montar Cero dentro de otra cosa.

## El hito 10, y por qué hizo falta

Las 131 pruebas propias estaban en verde y h2spec daba 130 de 146. Los 16 fallos venían de seis
causas, y la primera las explica casi todas: **hay dos juegos de ajustes por conexión y el puerto
escribió uno**. Los nuestros dicen lo que admitimos recibir; los del cliente, lo que él admite
recibir. Fundirlos hacía tres cosas a la vez, y solo una parecía un fallo:

1. **La salida se quedaba sin control de flujo.** Su `SETTINGS_INITIAL_WINDOW_SIZE` se aplicaba a
   la ventana de entrada, así que la de salida no existía: el servidor mandaba todo lo que tenía en
   cuanto lo tenía. Contra un cliente que lee rápido funciona.
2. **El cliente podía subir por SETTINGS los topes que lo contenían.** Con
   `SETTINGS_MAX_HEADER_LIST_SIZE` a 2^32-1 desaparecía `H2-039`, la defensa contra la bomba de
   descompresión de HPACK; con `SETTINGS_MAX_CONCURRENT_STREAMS`, el tope de flujos en vuelo. Dos
   defensas anuladas por una trama válida de seis octetos. Java no lo tenía: ignora los ajustes del
   cliente que no le tocan, y este fallo nació al portar.
3. **Se leía del cable con el tope del cliente en vez de con el nuestro.**

Las otras cinco causas están en `spec/http2.md` como `H2-043` a `H2-049`. Dos merecen contarse
porque no se ven leyendo el código:

**El GOAWAY se escribía bien y se perdía siempre.** Cerrar un socket con octetos sin leer en el
búfer de recepción no manda un FIN, manda un RST, y un RST borra en la otra punta lo que todavía no
había leído. El cliente veía «connection reset by peer» y nunca el motivo. Ninguna prueba propia
podía verlo: todas leen la respuesta antes de cerrar. Se arregla con media conexión y un vaciado
(§9.1), y de paso la suite de h2spec pasó de 35 segundos a 5: los timeouts eran esto.

**El tope de concurrencia no llegaba a tocar nunca.** Descontaba al flujo que ya había dicho
`END_STREAM` —a quien terminó de hablar no se le debe nada— y es justo ese el que tiene trabajo en
marcha, esperando respuesta. Encima el conductor lo daba por respondido al *despacharlo*, no al
responderlo. Ahora el hilo que responde se lo dice al que lee, que es toda la conversación que
necesitan tener.

## Pieza por pieza: lo que el código no dice solo

El código no lleva comentarios. Lo que explicaban, y no está ya contado arriba, va aquí.

**`servidor.rs`.** El bucle de aceptación y el pipeline. El middleware se compone con un índice y
no con una pila de clausuras: componer `Box<dyn Fn>` obliga a nombrar un tipo que se anida consigo
mismo, y con el índice «el siguiente» es `i + 1`. El primero declarado es el más exterior
(`RUT-028`), y corre también sin ruta (`RUT-029`): un 404 sin cabeceras de seguridad deja sin
proteger justo lo que más se provoca desde fuera. El manejador de fallos de la aplicación corre
antes que la regla por defecto, incluso sobre un 500 (`RUT-027`). Un verbo no admitido sigue siendo
405 aunque haya respaldo de SPA (`RUT-031`). Todo sale por `rematar`, un solo sitio para cabeceras,
cookie, métricas y log; el log anota el estado que **sale**, no el que la acción pretendía
(`OBS-012`). `sin_registrar` calla caminos por nombre —la sonda de salud—, nunca por adivinación.

**`contexto.rs` y `fallo.rs`.** El contrato no nombra nada de esto: solo dice qué está disponible.
`abrir_sesion()` es idempotente dentro de una petición, y la sesión abierta se recuerda para que el
punto que emite la cookie la vea. `token_csrf()` lo pide la aplicación porque emitirlo siempre
obligaría a abrir sesión a todo el que pase, y abre sesión porque un token sin sesión no protege.
Un `Fallo` con estado declarado conserva su mensaje (`RUT-026`); un 5xx no (`RUT-024`). `EnRespuesta`
tiene dos implementaciones y no tres: una para `Result<Respuesta, Respuesta>` haría ambiguo el
error de cualquier acción con `?`.

**`peticion.rs`.** Cada rechazo cita el requisito que lo exige. Se lee de un lector que **se
conserva entre peticiones**: uno nuevo por petición tira la siguiente de un cliente que las
encadena. Se acepta LF suelto pero no CR suelto, que es la vía del contrabando; los métodos
distinguen mayúsculas (`HTTP-007`); `Content-Length` con `Transfer-Encoding` se rechaza.

**`ruta.rs`.** Un patrón inválido se rechaza al construirlo (`RUT-006`, `RUT-007`): al arrancar es un
error, al resolver sería un 500.

**`registro.rs`.** Sin reflexión, cada servicio se registra con la función que lo construye, y un
tipo sin registrar no compila donde se usa. La fábrica corre **fuera** del candado: vuelve a entrar
por cada dependencia, y dentro sería un candado no reentrante bloqueándose solo. Un ciclo se cuenta
con la cadena entera, porque el tipo repetido solo dice dónde se cerró.

**`seguridad.rs`.** El contrato fija el comportamiento por defecto, no la política. El preflight
ajeno se rechaza (`SEG-013`) y la petición simple no (`SEG-010`): ya llegó, y quien decide es el
navegador. El limitador usa solo el cliente como clave (`SEG-022`).

**`observabilidad.rs`.** `vivo` no admite comprobaciones (`OBS-001`). Un error se registra con su tipo
(`OBS-010`), que en Rust es el tipo estático. Las métricas van por patrón de ruta y no por URL
(`OBS-014`): con la URL, `/usuarios/{id}` daría una serie por identificador.

**`sesion.rs`.** El identificador sale de la fuente del sistema operativo, que es lo que hace
`SecureRandom` por debajo (`SES-002`). Una sesión rescatada de un almacén no nace sucia ni con
cookie pendiente. `Sesiones::guardar` se llama una vez por respuesta, en el sitio que emite la
cookie; el almacén en memoria no tiene nada que hacer ahí.

**`validacion.rs`.** 422 y no 400, con detalle por campo. Las reglas se comprueban en orden y se
para en la primera que falla, y el cuerpo se devuelve **tal cual**: validar no es normalizar.

**`json.rs`.** Sin derivación automática, que pediría macros procedurales externas: la aplicación
construye el valor, y a cambio no hay reflexión en el camino caliente.

**`estaticos.rs`.** El camino se comprueba dentro de la raíz después de resolverlo.

**`http2/`.** Por capas, en el orden en que el contrato se puede comprobar: tramas, HPACK, flujos.
Casi todo en `trama.rs` son rechazos, porque un servidor permisivo desacuerda con el proxy de
delante. Los ajustes de una misma trama se acumulan en orden (§6.5.3). En `flujo.rs`, `Sesion` vive
lo que el socket; terminar de responder cierra solo nuestra mitad; entre HEADERS y su última
CONTINUATION no cabe nada; PRIORITY se descarta pero un flujo que depende de sí mismo se rechaza
(`H2-044`, `H2-049`); una mayúscula en un nombre no se corrige, se rechaza (`H2-019`); la forma de la
petición se valida aquí y no en el ruteo, porque una malformada no llega a existir; los topes de
inundación son política del servidor, holgados para un cliente normal y finitos para uno anormal.
`conexion.rs` lleva un hilo del sistema **por flujo** (`H2-029`), una salida con candado y no con
canal —una trama sale entera o no sale—, el cuerpo partido por el tamaño máximo de trama y por el
crédito, y el candado cogido por trama para no parar a los demás flujos mientras se espera
crédito. `:authority` pasa a `host`. El preámbulo se ojea sin consumirlo. `credito.rs` está aparte
porque lo gastan varios hilos y lo repone el que lee. En `hpack.rs`, el índice 1 es la entrada más
reciente; una entrada que no cabe vacía la tabla (§4.4); el entero de longitud variable tiene tope
de cinco septetos; el codificador no indexa. `hpack_tablas.rs` son tablas estáticas, tomadas de los apéndices A y B del RFC 7541 y no generadas por ningún guion; las respaldan la prueba de la suma de Kraft y los vectores del apéndice C.

**`cero-data`.** `Valor` no admite cualquier cosa: lo que el framework no sabe nombrar, el driver
tendría que adivinarlo. `memoria.rs` entiende exactamente lo que `Repositorio` emite y nada más, y
conserva los identificadores como se escribieron. `migraciones.rs` parte el guion sin romper
cadenas, comentarios ni bloques. En `sesiones.rs` el nombre de la tabla no tiene valor por omisión
(`SES-013`) y se valida, porque un identificador no puede ir parametrizado. El `Cargo.toml` declara
`cero-http` y nada más, y en ese sentido: `cero-data` implementa el contrato de sesiones que define
la capa HTTP, y al revés sería un ciclo.

**`bin/conforme.rs` y `bin/ejemplo.rs`.** El servidor del banco lee el cuerpo en `/eco`, porque sin
eso los vectores de encuadre no comprueban nada, y su raíz responde algo más largo que «ok» porque
h2spec necesita un cuerpo que no quepa en una ventana apretada: con dos octetos se salta la prueba
en vez de fallarla. El ejemplo usa todo el pipeline.

## Usarlo

```bash
cargo run --release --bin ejemplo 8080   # una aplicación de punta a punta
cargo run --release --bin conforme 8777  # y el servidor del banco de conformidad
```

El ejemplo levanta el pipeline entero: cabeceras de seguridad en toda respuesta —incluidos los
404—, CORS, límite de peticiones, CSRF, sesiones con cookie, salud en `/cero/vivo` y
`/cero/listo`, métricas por patrón de ruta y log de acceso.

## Dos fallos que solo aparecieron al montarlo entero

Los módulos pasaban sus 47 pruebas por separado. Conectarlos destapó dos cosas que ninguna
prueba de módulo podía ver, que es justo el argumento de `spec/ruteo.md` sobre contar caminos de
salida en vez de líneas.

**El CSRF tapaba el 405.** Corría antes del ruteo, así que un verbo no admitido recibía 403 en
vez de 405: la respuesta atribuía el fallo a la causa equivocada, que es lo que `RUT-009` prohíbe
al exigir distinguir 404 de 405. Ahora el ruteo va primero y el CSRF después, ya sabiendo que la
petición iba a alguna parte.

**La sesión creada dentro de la acción no emitía su cookie.** `abrir_sesion()` la creaba, pero el
punto que emite la cookie solo miraba la sesión que había llegado *con* la petición. El cliente
abría sesión y no recibía nada. Es la familia de `SES-010` otra vez —el estado y el sitio que lo
escribe, separados— con otra cara.

## Lo que faltaba para que fuera usable

Hasta el hito 5 era un framework que pasaba el contrato y con el que no se podía escribir una
aplicación: para devolver un objeto había que construir el JSON a mano, no se podía leer un
formulario y no servía un solo archivo. Eso no es un framework, es un experimento.

**JSON propio.** Java tiene el suyo en `cero-core` por el mismo motivo: un framework que obliga a
traer una biblioteca de JSON para devolver un objeto no tiene cero dependencias, tiene una
escondida. El de aquí lee y escribe, con tres cosas que no son adorno:

- Las claves salen **en orden estable**, así que dos respuestas iguales dan bytes iguales y se
  pueden comparar y cachear. `HashMap` no lo garantiza.
- Se escapan `<`, `>` y `&`. Un JSON incrustado en una página que contenga `</script` cierra la
  etiqueta y lo que siga se ejecuta: es XSS a través de una respuesta perfectamente válida.
- Hay **tope de anidamiento**. 5 000 corchetes son 10 KB de cuerpo y desbordan la pila de un
  lector recursivo: denegación de servicio con una petición pequeña.

**Formularios y JSON de entrada.** `cuerpo_json()` y `campo()`. Un cuerpo mal formado es 400 y no
500: lo mandó mal el cliente.

**Archivos estáticos.** Con la comprobación que de verdad importa: el camino se resuelve y
**después** se comprueba que sigue dentro de la raíz. Filtrar `..` antes no basta, porque un
enlace simbólico dentro de la raíz apunta fuera sin que aparezca ningún `..`. Y lo que no se
reconoce se sirve como octetos, nunca adivinando el tipo — adivinar es justo lo que
`nosniff` existe para impedir del otro lado.

## TLS y datos: las dos preguntas difíciles, respondidas

Son los dos sitios donde «cero dependencias» parecía imposible en Rust. Una tenía mejor respuesta
de la que parecía y la otra no tiene ninguna buena, y conviene decir cuál es cuál.

### Datos: no era un problema, y creerlo era un error de lectura

`cero-data` en Java **no trae driver**. Su `pom.xml` declara PostgreSQL solo en ámbito de prueba,
y el LEEME del proyecto ya lo dice: «el driver JDBC lo pone la aplicación, y es la única
excepción». Lo que Java hereda de la plataforma es la **interfaz** —`java.sql`—, no el motor.

`std` de Rust no trae esa interfaz, así que aquí se define. El reparto queda idéntico: el
framework pone el contrato, la aplicación pone el driver. `cero-data` no declara ninguna
dependencia de ejecución y **se prueba entero sin base de datos**, con una implementación en
memoria; Java usa H2 para lo mismo, que sí es una dependencia de prueba.

Definir la interfaz en vez de heredarla deja además dos cosas mejor que en Java:

- **No existe un método que acepte SQL ya interpolado.** En Java es una convención que hay que
  respetar; aquí habría que escribir la interpolación a mano para saltársela.
- **Una transacción no puede quedarse a medias por olvido.** En Java es un `try`/`catch` que hay
  que escribir bien cada vez; aquí la única forma de tener una transacción es pasar por la función
  que la deshace si el cuerpo falla.

### TLS: aquí no hay buena respuesta, y escribirlo nosotros sería la peor

TLS 1.3 pide aritmética de curva elíptica, AEAD, HKDF, parseo de X.509 y validación de cadenas de
certificados. Son decenas de miles de líneas de criptografía donde un fallo sutil es una
vulnerabilidad remota y silenciosa, y donde buena parte de la corrección —resistencia a canales
laterales— **no se puede comprobar desde fuera**.

Ese último punto es el que decide, y no es el esfuerzo. Este proyecto sostiene sus afirmaciones
con evidencia: 23 vectores de conformidad, h2spec, un banco reproducible. Para una pila TLS
escrita a mano **no tendríamos con qué**, y publicarla sería exactamente la clase de afirmación
sin medir que el resto del proyecto se niega a hacer.

Java tampoco escribió la suya: usa la del JDK, mantenida por un equipo con proceso de CVE.

Queda una salida honesta y es la que ya usa el despliegue real: **terminar TLS fuera del
proceso**. El sitio en producción corre detrás de nginx hoy, con la implementación en Java que
sí trae TLS. El requisito del contrato que depende de esto —que la cookie sea `Secure` cuando y
solo cuando la conexión es segura— se cumple sabiendo si la conexión llegó cifrada, y eso se
resuelve con confianza en proxy, que Cero ya tiene en Java.

**Y esto es un resultado, no una excusa.** Con la concurrencia se pudo cambiar de modelo y cumplir
igual; aquí no. Hay partes del contrato que un lenguaje no puede cumplir sin dependencias, y no
por una decisión de diseño sino porque su biblioteca estándar es más pequeña. Un contrato
poliglota debería decir qué requisitos son de esa clase.

## Una nota sobre el contrato, no sobre el código

El vector de `OPTIONS *` fija **200**, y al montar el pipeline se devolvió 204, que también es
una respuesta sin cuerpo razonable. Se cumple el contrato, porque es lo que manda mientras haya
discrepancia, pero conviene comprobar si el RFC **exige** 200 o solo lo permite. Un contrato que
fija una elección que la norma deja abierta está especificando de más, y eso ata a las
implementaciones futuras sin motivo.

```bash
cd rust && cargo test          # las pruebas de la implementación
cargo run --bin conforme 8777  # un servidor mínimo contra el que correr los vectores
```

## Sin dependencias, y lo que eso obliga a cambiar

`Cargo.toml` no declara ninguna, igual que los módulos de Java no declaran ninguna fuera del JDK.
Esa regla, que en Java era casi gratis, aquí cuesta de inmediato — y esa es exactamente la
pregunta del proyecto: **¿qué supuestos mete un lenguaje en un diseño sin que su autor se dé
cuenta?**

**Primer hallazgo, y aparece en la primera línea del servidor.** Java resuelve la concurrencia con
un hilo virtual por conexión, y los hilos virtuales están *en la plataforma*: no son una
dependencia. La biblioteca estándar de Rust no tiene nada equivalente. Tiene hilos del sistema, y
tiene `async`/`await` como sintaxis **pero sin runtime que la ejecute**: cualquier runtime es una
caja externa.

Así que «cero dependencias» en Rust fuerza **un hilo del sistema por conexión**, que es un modelo
distinto: más caro por conexión y con un tope mucho más bajo. Las opciones eran tres y ninguna
sale gratis:

| Opción | Qué cuesta |
|---|---|
| Hilo del sistema por conexión | Se mantiene la regla; el modelo de concurrencia deja de ser el mismo |
| Un runtime async externo | Se rompe la regla que define el proyecto |
| Escribir el runtime | Deja de ser un framework web y pasa a ser otra cosa |

Se toma la primera, y **el contrato no cambia**: ningún requisito de `spec/` habla de hilos. Que
eso siga siendo cierto cuando lleguen las sesiones y la concurrencia es justamente lo que hay que
comprobar, no lo que se puede suponer.

## Segundo hallazgo: el lenguaje decide si un requisito se puede incumplir por descuido

`SES-011` dice que consultar la cookie pendiente **no es una lectura pura**: marca la cookie como
emitida, así que repetirla la pierde. En Java eso es una nota en el contrato y una disciplina que
hay que recordar — y no se recordó: Cero 0.6.0 tenía dos caminos de salida, uno por versión del
protocolo, y solo uno preguntaba. Sobre HTTP/2 no había sesión, y sin sesión no había token CSRF.

En Rust el método toma `&mut self`. Dos caminos de salida **no pueden consultarlo los dos** sin
que el compilador lo señale. El requisito es el mismo y el comportamiento exigido es el mismo; lo
que cambia es que en un lenguaje se puede incumplir por descuido y en el otro no.

Eso no es una virtud de Rust que haya que celebrar: es un dato sobre qué parte del contrato
depende de la disciplina del programador y qué parte puede delegarse al tipo. Un contrato
poliglota debería decir cuáles de sus requisitos son de esa clase, y hoy no lo dice.

**El hito 3 lo repitió, y con una consecuencia incómoda.** `SEG-022` dice que la cuota del
limitador no puede depender de la ruta. En Java eso es comprobable, porque la función recibía la
ruta y había que verificar que no la usara. Aquí no la recibe: **el incumplimiento no se puede
escribir**. Se intentó reintroducir el fallo para ver si la prueba lo cazaba, y no lo cazó porque
no había fallo que cazar.

Eso deja la prueba vacía, y una prueba que no puede fallar es peor que ninguna: ocupa sitio y da
confianza que no ha ganado. Se sustituyó por otra que sí verifica algo —que el mapa de cuentas no
crece con las peticiones— y el requisito quedó anotado como cumplido por la forma del tipo.

Con dos casos ya se ve el patrón, y es material del artículo 6: **el contrato tiene requisitos de
dos clases**, los que hay que probar y los que se pueden hacer irrepresentables. Cuál es cuál no
lo decide el requisito: lo decide el lenguaje.

## Tercer hallazgo: «lanzar» no significa lo mismo en los dos lenguajes

`OBS-005` dice que una comprobación de salud **que lanza** debe dar 503 y no 500: lanzar es una
forma de fallar, no un fallo del endpoint. En Java eso es atrapar una excepción, que es el
mecanismo normal de error del lenguaje.

En Rust el error normal es un valor —`Result`—, y lo que corresponde a «lanzar» es `panic!`, que
es una condición excepcional y no un modo de error corriente. Atraparlo existe
(`catch_unwind`) pero es raro y algunas configuraciones lo desactivan.

El requisito se cumple y el comportamiento observado es el mismo, pero **el requisito estaba
escrito con el vocabulario de un lenguaje**. «Que lanza» no es neutral: en Rust habría que decir
«una comprobación que falla de forma no prevista». Es una contaminación más leve que las dos
anteriores —no cambia el diseño, solo la redacción— y por eso es fácil que se cuele.

## Lo que este hito **no** hace

- No hay TLS ni WebSocket.
- HTTP/2 está a medias: tramas y HPACK sí, flujos y control de flujo no.
- Las sesiones viven en memoria: `SES-012` —almacén compartido entre instancias— no está.
- No hay API de aplicación estable. Lo que hoy se llame `Servidor` puede llamarse otra cosa
  mañana: el contrato es el comportamiento, no los nombres.
