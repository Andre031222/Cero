# cero-http

El servidor: HTTP/1.1 y HTTP/2 sobre un hilo virtual por conexión, TLS, sesiones, estáticos,
WebSocket y eventos del servidor. Sin dependencias fuera del JDK.

El código no lleva comentarios. Lo que explicaban y el código no dice solo está aquí.

## El modelo: un hilo virtual por conexión

Todo el módulo se apoya en eso. Una conexión la atiende un solo hilo, así que lo que vive en la
conexión no necesita ser seguro entre hilos: `AsciiBuffer`, el búfer donde se arma la cabecera de
la respuesta, se reaprovecha entre peticiones sin sincronizar nada. Y un hilo que se queda dentro
de un bucle —un WebSocket, un flujo de eventos— no cuesta un hilo del sistema: mil paneles
abiertos son mil hilos virtuales. Con hilos de plataforma `Sse` y `WebSockets` no se podrían
escribir así.

## Las clases públicas

- **`Server`, `ServerOptions`, `Handler`, `Request`, `Response`.** La puerta de entrada. Lo que
  hay que saber de `ServerOptions` está en sus topes, más abajo.
- **`Duplex`.** La conexión en crudo después de cambiar de protocolo (`Response.switchProtocols()`
  escribe el `101` y la devuelve). Quien la recibe es su dueño hasta que termina; el servidor ya no
  la toca y la cierra al volver.
- **`Session`, `SessionStore`.** La sesión y dónde viven sus datos. Por defecto en memoria, que
  basta para una instancia. Detrás de un balanceador hace falta un almacén compartido, y ese hueco
  es la interfaz: Cero no trae ninguno remoto porque sería una dependencia. `cero-data` trae
  `JdbcSessions`. Se escribe en cada cambio y no al final de la petición, para que dos instancias
  nunca vean datos distintos.
- **`Session.regenerateId()`** se llama **justo después de identificarse**. Si no, el
  identificador de antes de entrar sigue valiendo después, y quien lo hubiera fijado —desde un
  subdominio, un kiosco compartido— se queda con una sesión autenticada. A diferencia de
  `invalidate()`, no pierde nada: ni el token CSRF ni lo que guardó el flujo de acceso.
- **`Sse`.** Eventos del servidor al navegador, en una sola dirección. Frente a un WebSocket es
  HTTP normal: atraviesa proxies sin configurar nada y se reconecta solo. Para un panel, un
  progreso o un aviso es lo que hace falta.
- **`WebSockets`, `WebSocket`, `WebSocketHandler`.** RFC 6455. Enviar es seguro desde cualquier
  hilo, porque los envíos se serializan sobre la salida; leer lo hace el hilo de la conexión. Un
  fallo de red al enviar sale como `WebSocket.UncheckedIoException`, para no obligar a declarar
  `IOException`. Los métodos del manejador vienen vacíos.
- **`StaticFiles`.** Estáticos del disco o del classpath, con `Range`, `ETag` y `Cache-Control`.
- **`Tls`.** `Tls.reloadable(...)` da un contexto cuyo certificado se cambia sin reiniciar: el
  `SSLContext` es siempre el mismo objeto, porque el socket de escucha se creó con él, pero
  pregunta por el certificado en cada apretón de manos. Las conexiones abiertas siguen con el
  viejo y las nuevas usan el nuevo.

## HTTP/2

Una aplicación no se entera: recibe el mismo `Request` y el mismo `Response` que en HTTP/1.1.
Está la capa de tramas, HPACK completo, flujos concurrentes, control de flujo por conexión y por
flujo, SETTINGS, PING, RST_STREAM y GOAWAY ordenado. Se entra por tres puertas:

- **ALPN sobre TLS**, la única por la que llega un navegador, que no habla h2c en claro. Se
  anuncian `h2` y `http/1.1` en ese orden. `http/1.1` no se quita: quien no sabe de h2 no manda
  ALPN, y otro puede pedir 1.1 explícitamente.
- **Conocimiento previo.** En claro hay que ojear el preámbulo y devolverlo al flujo, porque
  `PRI * HTTP/2.0` es también una petición HTTP/1.1 sintácticamente válida.
- **`Upgrade: h2c`**, solo en la primera petición de la conexión: cambiar a mitad no está definido.

No está **PUSH_PROMISE**, en desuso; se anuncia deshabilitado en SETTINGS. **PRIORITY** se lee y se
descarta, que es lo que permite el RFC 9113. Se apaga entero con `ServerOptions.http2(false)`.

Decisiones de dentro:

- **El estado de un flujo se deduce, no se guarda.** Un identificador mayor que el último visto
  está ocioso; uno menor que ya no está entre los abiertos está cerrado. Guardar los cerrados sería
  memoria que un cliente haría crecer a voluntad. La distinción importa porque el RFC pide errores
  distintos: una trama sobre un flujo ocioso rompe la conexión y sobre uno cerrado solo ese flujo,
  porque puede ser una carrera legítima con un RST_STREAM.
- **HPACK es estado compartido.** Si el decodificador se desincroniza del cliente, fallan todas las
  cabeceras siguientes, así que cualquier error de HPACK es de conexión. El codificador **no usa
  tabla dinámica**: a cambio de unos bytes por respuesta, no existe la clase de fallo en que las
  dos tablas se desalinean.
- **Las tablas fijas de HPACK (`HpackTablas`) son tablas estáticas tomadas de los apéndices A y B del RFC**;
  ningún guion las genera. Las respalda que la estática tenga 61 entradas exactas y que la suma de Kraft del
  código de Huffman valga 1. Una tabla de 257 filas copiada a mano no revienta: decodifica mal.
- **La respuesta (`Http2Respuesta`)** traduce lo que en HTTP/2 no existe: el estado va como
  `:status` y primero; `Connection`, `Keep-Alive` y `Transfer-Encoding` se filtran; los nombres van
  en minúscula, que no es estilo sino validez.
- **`stream()` sale en tramas según se escribe**, no al final. Va con un búfer de 8 KB para que
  quien escriba byte a byte no genere una trama por byte, y `flush()` lo suelta enseguida. Las
  cabeceras salen en la primera escritura: quien espera el primer byte necesita el `:status`.
- **El cuerpo de la petición es un `InputStream`** que se llena por DATA mientras el manejador ya
  corre. Así multipart, JSON y formularios funcionan sin saber que debajo hay tramas.

### Los topes contra inundaciones

Se pueden subir, pero conviene saber contra qué protege cada uno:

| Opción | Contra qué |
|---|---|
| `http2MaxBloqueCabeceras` | CVE-2024-27316: HEADERS y CONTINUATION sin fin, ninguna inválida por separado |
| `http2MaxListaCabeceras` | La bomba de HPACK: tres kilobytes comprimidos pueden ser trescientos. Se anuncia en `SETTINGS_MAX_HEADER_LIST_SIZE` |
| `http2MaxAnulados` | CVE-2023-44487, «Rapid Reset»: abrir y anular saca el flujo del tope al instante |
| `http2MaxControlSeguidas` | PING, SETTINGS, WINDOW_UPDATE y PRIORITY sin abrir flujos; abrir uno reinicia la cuenta. Un `WINDOW_UPDATE` de un flujo, o de la conexión con algún flujo vivo, no cuenta: es lo que hace quien descarga |
| `http2MaxEnVuelo` | «HTTP/2 Bomb» y el lector lento: 32 MiB de respuestas construidas y sin mandar por conexión; pasarlos es GOAWAY `ENHANCE_YOUR_CALM` (`H2-054`). Una respuesta que llega sola pasa siempre, para no romper una descarga grande |
| `maxBodyBytes` | también en h2: un `content-length` de más recibe 413 sin que corra la acción, y un cuerpo sin longitud que pasa de 10 MiB deja de guardarse y recibe 413 si la respuesta no ha empezado, o `RST_STREAM NO_ERROR` si ya empezó; lo que la acción escriba después se descarta (`H2-055`) |

Y uno que no se configura: un bloque que se expande a más de **256 campos** corta la conexión
(`H2-050`), cada `cookie` por separado. La lista se mide mientras se decodifica, campo a campo, y no
al terminar: una lista expandida que se deja crecer hasta el final ya ocupó la memoria (`H2-051`).

### Los plazos

Tres, y ninguno admite 0 ni negativo, igual que `http2MaxEnVuelo` y `maxBodyBytes`: `ServerOptions`
lanza `IllegalArgumentException` y el servidor no llega a escuchar (`HTTP-025`). Antes, 0 quería decir «sin plazo», que es justo la errata que
deja una conexión abierta para siempre.

| Opción | Por defecto | Qué cubre |
|---|---|---|
| `idleTimeoutMillis` | 30 s | la espera de la siguiente petición en HTTP/1.1 (`HTTP-024`) y, en h2, el tiempo sin `HEADERS` ni `DATA` mientras ningún flujo trabaja; `PING` y compañía no lo reinician (`H2-053`) |
| `handlerTimeoutMillis` | 30 s | lo que tarda un manejador en HTTP/1.1; una conexión que ya cambió de protocolo —un WebSocket— deja de contar |
| `writeTimeoutMillis` | 60 s | cada trama de h2: escribirla en el socket y, antes, esperar su crédito. Un flujo sin ventana en ese plazo se corta con `RST_STREAM CANCEL`, aunque el cliente gotee `WINDOW_UPDATE` de un octeto (`H2-052`). Y cada escritura de un WebSocket: un cliente que no lee no retiene el hilo ni el socket |

`handlerTimeoutMillis` a 0 era también la forma de no cortar un WebSocket a los 30 s. Ya no hace
falta: el plazo del manejador se suelta en cuanto la respuesta cambia de protocolo, y desde ahí lo
que vigila el `Watchdog` es cada escritura, con `writeTimeoutMillis`. Es un cambio de API: quien
usara 0 para tareas largas tiene que poner un número.

Un flujo rechazado por el tope de flujos decodifica su bloque igual: la tabla dinámica de HPACK es
de la conexión, y saltárselo descolocaba las cabeceras de las peticiones siguientes (`H2-035`).

El servidor del banco es `Conforme`, entre las pruebas:
`java -cp target/classes:target/test-classes cero.http.Conforme 8777`. Con un segundo argumento,
en milisegundos, fija `writeTimeoutMillis` y el doble en `idleTimeoutMillis`, que es como lo arranca
`spec/banco/ataques.py`.

## Proxies

- **`behindProxy(true)`** hace caso a `X-Forwarded-Proto` para saber si la petición original era
  HTTPS y marcar la cookie `Secure`. Sin declararlo, cualquier cliente podría decir que su petición
  es segura.
- **`trustProxy(...)`** hace caso a `X-Forwarded-For` solo si quien habla está en la lista. Nunca
  está activa por defecto: creerse esa cabecera venga de donde venga permite saltarse el limitador
  y ensuciar los registros. Sin ella, detrás de un proxy todos comparten la IP del proxy y el
  primero que llega al tope bloquea el sitio entero.

## Sesiones

- **`sessionMaxLifetime`** pone un tope de vida total. Apagado por defecto; para un panel de
  administración conviene, porque sin él una sesión que se toca de vez en cuando no caduca.
- **El último acceso no se escribe en cada petición**, sino cuando ha pasado una décima del tiempo
  de expiración. Contra una tabla, lo otro sería un `UPDATE` por petición para mover una marca.

## Estáticos

- **`cacheControl`** para los recursos y **`cacheControlDocumento`** para el HTML, separados a
  propósito. Un recurso con huella en el nombre puede ser `immutable` un año porque si cambia
  cambia su nombre; un `index.html` no tiene huella, y servirlo inmutable congela la página entera.
  Por eso el documento va con `no-cache` por defecto y nunca sin cabecera, que dejaría al navegador
  cachearlo horas por heurística.
- **`spa()`** devuelve `index.html` para una ruta que no existe, solo si se pide HTML o la ruta no
  tiene extensión: un `.css` que falta sigue siendo 404.
- **`ruta/index.html` gana sobre `ruta`**, porque una carpeta del classpath también «existe» y
  leerla devuelve su listado.
- **La caché del classpath solo guarda lo que existe y cabe.** La clave la elige quien pide, así
  que guardar las ausencias dejaba llenar el montón con rutas inventadas. Pasado el tope se sirve
  leyendo cada vez.
- **`Range`** admite un solo intervalo; con varios se sirve el recurso entero, que el RFC permite.
  Los rangos grandes se transmiten igual que el archivo completo.

## Detalles del camino caliente

- **Los nombres de cabecera frecuentes salen de una tabla** de constantes: recortar el nombre era
  la tercera fuente de asignación del camino caliente, medido con Flight Recorder bajo 64
  conexiones.
- **La respuesta no se vacía al terminar el manejador**, sino cuando acaban él y su middleware.
- **`chunked` tiene que ser la última codificación** (RFC 9112 §6.1); si no, no se sabe dónde acaba
  el mensaje.
- **Una redirección a un destino externo** —con esquema o `//otro.host`— se reconoce, porque si
  viene de la entrada del usuario es una redirección abierta.
- **`Watchdog`** corta las peticiones que se pasan de tiempo: un hilo barre las conexiones y
  aborta las que llevan demasiado dentro del manejador. Por petición cuesta dos escrituras de un
  `volatile`.
- **El apagado cierra primero las conexiones ociosas.** Una conexión en keep-alive está bloqueada
  leyendo y `shutdown()` no la interrumpe: sin esto, el apagado gasta la ventana de gracia entera
  esperando a nadie. La marca de ociosa es propia y no reutiliza el límite del manejador, que solo
  se pone cuando hay límite; con él a cero, una petición en vuelo pasaría por ociosa.
