# cero-core

El framework sobre `cero-http`: router, middleware, inyección, JSON, validación, seguridad,
observabilidad y lo que una aplicación suele necesitar alrededor. Sin dependencias fuera del JDK.

El código no lleva comentarios. Lo que explicaban y el código no dice solo está aquí.

## Lo que se decide al registrar y no en cada petición

`RouteEntry` y `Binder` existen por eso. Lo que la reflexión puede decir de una ruta
—accesibilidad, anotaciones de autorización, de dónde sale cada argumento— se resuelve una vez, al
registrarla; en cada petición solo se recorre el plan. Es también el paso previo a resolverlo en
tiempo de compilación, que es lo que hacen Rust y C++.

## Controladores

- **`Controller` es opcional.** Heredar no cambia cómo se enruta: siguen mandando `@Route` y
  `@Get`. Solo ahorra arrastrar el `Context` por parámetro y da un sitio para lo que se repite. Las
  dos formas conviven.
- **No guarda la petición en un campo.** Hay una sola instancia por controlador y un hilo virtual
  por conexión: un campo lo pisarían dos peticiones a la vez y una respondería con los datos de la
  otra. Todo sale de **`Current`**, un `ThreadLocal` que pone y quita el `Dispatcher` en un
  `try/finally`. Con hilos virtuales es barato, y fuera de una petición preguntar da error en vez
  de devolver el contexto de otro.
- **El respaldo de una ruta inexistente** —los estáticos de un SPA— va **dentro** del despachador.
  Delante, el documento HTML se saltaba `SecurityHeaders` y `RateLimit`: la página quedaba sin
  `X-Frame-Options` y sin freno, y la API sí los tenía.

## Inyección

`Registry` construye **fuera** del mapa concurrente, con un cerrojo por tipo. Resolver dentro de
`computeIfAbsent` anida llamadas sobre el mismo `ConcurrentHashMap`, y en cuanto dos eslabones de
la cadena caen en el mismo bin lanza `Recursive update`. No era un límite de profundidad: era una
colisión que con cadenas largas deja de ser casualidad.

## Seguridad

- **`RateLimit`**: la ruta **no** entra en la clave. Cuando entraba, el mapa crecía con cada ruta
  inventada y repartir la carga entre URLs multiplicaba la cuota. Quien quiera granularidad por
  ruta la pide con `keyBy`.
- **`Csrf`**: una exención por prefijo solo vale si el corte cae en una barra; con `startsWith` a
  secas, eximir `/api/publico` eximía `/api/publicoSECRETO`.
- **`Sanitize`** reconoce la entrada y reescribe la salida solo con lo permitido; nada pasa tal
  cual.
- **`Passwords`**: PBKDF2-HMAC-SHA256 del JDK. El formato guardado lleva su propio coste, para
  subirlo sin invalidar lo almacenado.
- **`OAuth`**: OAuth 2.0 / OpenID Connect con PKCE obligatorio; trae Google, Microsoft y GitHub. Al
  verificar un `id_token` comprueba algoritmo, audiencia, emisor y caducidad, y rechaza
  `alg: none`.
- **`Result.download`** limpia el nombre de archivo: unas comillas o un salto de línea de fuera
  partirían la respuesta e inyectarían cabeceras. **`Result.bytes`** existe porque devolver binario
  como `String` lo escribe en UTF-8 y el archivo llega, pesa parecido y no abre; devuelve el array
  sin copiar porque quien lo recibe solo lee.

## Observabilidad

- **`Health`**: `/cero/vivo` y `/cero/listo` no son lo mismo. Un proceso puede estar vivo y no
  poder atender; confundirlos hace reiniciar por una base de datos lenta o no reiniciar nunca uno
  colgado. `vivo` no admite comprobaciones, porque dejaría de medir lo que dice. `listo` responde
  503 si alguna falla —un supervisor mira el código, no el cuerpo— y una que lanza cuenta como
  fallo con su mensaje. Las comprobaciones deben ser baratas y con límite propio: se consultan
  cada pocos segundos y una lenta se apila consigo misma. Las rutas se registran en
  `Cero.health()` y no en `start()` para que cuenten en el resumen del arranque.
- **`Trace`**: W3C Trace Context, que entienden Jaeger, Tempo, Zipkin, Datadog y OpenTelemetry. El
  identificador aparece solo en cada línea de `Log`, en el log de acceso y en las llamadas de
  `Http`, que es todo el motivo de que exista: un trazado que hay que pasar a mano se olvida. No
  exporta tramos; eso es de un agente. Una cabecera inválida se descarta y se empieza una traza
  propia, sin 400; el tramo es siempre nuevo; no se pasa a minúscula, porque la especificación
  solo admite minúscula. El middleware quita la traza al salir: con keep-alive el mismo hilo
  atiende varias peticiones. `middlewareCallado()` no devuelve `Trace-Id` al cliente.
- **`Context.trace()`** sirve para enseñar el número a quien mira la pantalla y lo pega en un
  ticket.

## Textos e idiomas

`Messages` no usa `ResourceBundle` a propósito: este cae al idioma **del sistema** cuando falta
una clave, y el mismo despliegue responde distinto según el servidor. Aquí la cadena es idioma
pedido, idioma base, la propia clave.

- Los idiomas se nombran al cargar, porque el classpath dentro de un jar no se puede listar.
- El archivo sin sufijo entra directamente como idioma base (castellano salvo que `base()` diga
  otra cosa). Antes quedaba bajo una clave vacía y quien no llamaba a `base()` se quedaba sin él.
- Un texto que falta devuelve la clave y avisa por consola **una vez por clave**.
- `MessageFormat` solo si hay argumentos: trata las comillas simples como escape y estropearía un
  «d'accord».
- `negociar` respeta el factor de calidad de `Accept-Language`, que es lo que separa «prefiero» de
  «acepto a regañadientes».
- `Context.t()` sin mensajes configurados devuelve la clave en vez de lanzar, y el idioma se
  resuelve una vez por petición.

## Configuración y perfiles

- **`Config`** traduce `CERO_SERVER_PORT` a `server.port` en una función aparte para poder
  probarla: `System.getenv()` no se toca dentro del proceso, y ahí se escondió un fallo al
  renombrar el framework. Al final del arranque avisa de las variables `CERO_*` que nadie leyó,
  como las escritas en camelCase, que pasan a minúsculas y nunca casan.
- **`Profiles`** es un objeto y no un bloque estático, para que una prueba monte el suyo. Se pisa
  por `-Dcero.profiles` y luego por `CERO_PROFILES`; sin nada, `default`.

## Lo de alrededor

- **`Cache`**: sin registro global y sin hilo de limpieza. Lo caducado se descarta al leer y se
  barre al llenarse; para barrer periódicamente, `sweep()` en un `Cron`.
- **`Events`**: los oyentes corren en el hilo de quien publica, en orden de registro, y uno que
  falla no tumba a los demás. `@Listens` toma el tipo del evento de su único parámetro.
- **`Tasks`**: lanzar y olvidar, esperar, repetir y cron por una sola puerta, cada tarea en su
  hilo virtual. **`Cron`** acepta cinco campos con `*`, listas, rangos y pasos; el domingo es 0 y
  7.
- **`Mail`**: SMTP sobre `Socket`, porque JavaMail no está en Java SE. STARTTLS por defecto en el
  587; `ssl(true)` para el 465.
- **`Http.sse`** consume eventos del servidor como los transmiten las APIs de IA; `[DONE]` cierra.
- **`OpenApi`** se construye desde el `Router` y no escaneando el disco, así que describe también
  las rutas declaradas con lambda.
- **`Json`**: los tipos de `java.sql` heredan de `java.util.Date` y van antes que él. `time` y
  `date` son etiquetas de calendario, no instantes: tratarlos como instantes convertía las 19:00 en
  1970 y adelantaba un día las fechas.
