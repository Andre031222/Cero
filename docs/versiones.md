# Versiones

Cada versión publicada es **inmutable**: mismo número, mismo `sha256`, siempre. Si el contenido
cambia, cambia el número.

Eso no fue así al principio, y conviene decirlo: durante el 3 de agosto de 2026 el paquete
`luxcore-0.2.0.tar.gz` se rehizo unas ocho veces sin cambiar de número. Quien instalara por la
mañana y quien instalara por la noche tenían frameworks distintos y ninguna forma de saberlo. Lo
señaló la auditoría del portal FINESI, y de ahí sale esta regla.

---

## 0.5.0 · sin publicar

**El framework se llama Cero.** *LuxCore* chocaba con un framework PHP del mismo entorno.
*Corvo* resolvió esa confusión pero no decía nada del framework: era un nombre correcto y mudo.
**Cero** sí dice algo, y es lo mismo que dice la primera línea del LEEME —cero dependencias en
ejecución, cero configuración para arrancar, cero contenedor de servlets—. No es una metáfora
que haya que explicar: es la lista de lo que este framework no te obliga a tener.

Se escribe **Cero**, con mayúscula, también a mitad de frase: es una palabra común del
castellano y en minúscula desaparece dentro del texto. En artefactos y órdenes va en minúscula,
como siempre: `cero-core`, `cero new`.

### Java 25

**Sube el mínimo de JDK 21 a JDK 25**, y es lo único de esta versión capaz de impedir que
arranque una aplicación que hoy funciona. Va aquí arriba y no entre los arreglos por eso: el
renombrado se resuelve con una orden, pero un servidor que corre un JRE 21 no ejecuta este jar
—ni con el nombre viejo ni con el nuevo—.

El motivo es de plataforma, no de código: nada del framework usa una función posterior a la 21.
Se sube para no sostener dos objetivos de compilación a la vez, y porque el despliegue de
referencia ya corre sobre Temurin 25.

Comprueba las dos máquinas, que no son la misma: la que compila y la que ejecuta. Ver
[migrar-a-cero.md](migrar-a-cero.md).

### Qué cambia de nombre

- Paquetes `corvo.*` → `cero.*`. Clases `Corvo` → `Cero` y `CorvoServlet` → `CeroServlet`.
- Coordenadas `dev.ginit.corvo:corvo-*` → `dev.ginit.cero:cero-*`.
- La orden `./corvo` pasa a `./cero`.
- Configuración: `corvo.*` → `cero.*`, `CORVO_*` → `CERO_*`.
- Cookie de sesión `CORVOSESSION` → `CEROSESSION`.
- Métricas `corvo_*` → `cero_*`, y los endpoints `/corvo/*` → `/cero/*`.
- Tablas por defecto `corvo_migraciones` y `corvo_sesiones` → `cero_*`.

`./cero migrar <ruta>` convierte una aplicación entera, y **acepta los dos nombres viejos**: una
aplicación que se quedó en LuxCore sin pasar por Corvo llega a Cero en un solo paso. Ver
[migrar-a-cero.md](migrar-a-cero.md), que además explica las tres cosas que ninguna herramienta
puede hacer sola: renombrar las tablas antes de arrancar, que la cookie cierra todas las sesiones
al desplegar, y que los paneles de Grafana se quedan vacíos —sin dar error— hasta que se
actualicen las métricas.

**No hay capa de compatibilidad, a propósito.** Las aplicaciones que usan el framework son todas
nuestras: aceptar los nombres viejos en tiempo de ejecución significaría arrastrar código muerto
para siempre. El guion los acepta; el framework, no.

### Seguridad

Salen de una auditoría del código completo. Cuatro de los cinco hallazgos estaban en lo añadido
en 0.4.0, que era lo menos rodado.

- **`Sanitize.html` no saneaba.** Era una lista negra a base de expresiones regulares, y una
  lista negra de HTML siempre tiene un agujero más: `<svg/onload=alert(1)>` salía intacto porque
  el patrón de los manejadores exigía un espacio antes de `on…` y una barra no lo es.

  Ahora **nada de la entrada llega a la salida tal cual**: se reconoce lo que hay y se reescribe
  la salida solo con las etiquetas y los atributos de una lista de permitidos. Un atributo que no
  está en la lista no se examina para ver si es peligroso — no se emite. Las direcciones se
  juzgan tras deshacer las entidades y quitar los caracteres de control, porque el navegador
  también las deshace. Hay **166 aserciones** con un corpus de vectores conocidos.

- **`Live` repartía zonas privadas a cualquiera.** Una zona es un canal de difusión y el nombre
  lo manda el navegador, así que `pedidos-42` se lo llevaba quien escribiera `pedidos-42`. Nuevo
  `Live.autorizar((peticion, zona) -> …)`, que decide por conexión y por zona.

- **El apretón de manos de WebSocket no miraba `Origin`.** Un WebSocket lleva las cookies del
  sitio y la política del mismo origen no lo protege, así que cualquier web podía abrir el canal
  con la sesión de quien la visitara. Ahora solo el mismo origen, más los que se declaren con
  `Live.origenes(...)`.

- **`Live` crecía sin freno.** Sin tope de zonas por conexión, y el conjunto vacío se quedaba en
  el mapa para siempre. Tope de 32 zonas por socket, y la zona desaparece cuando se va el último
  oyente.

- **CORS admitía comodín con credenciales.** `Cors.anyOrigin().credentials(true)` devolvía el
  `Origin` de quien preguntara junto a `Access-Control-Allow-Credentials: true`, que es
  exactamente el agujero que el navegador prohíbe al vetar `*` con credenciales. Ahora falla al
  construirse, no al ejecutarse.

- **OAuth no comprobaba el `state`.** El javadoc mandaba guardarlo en la sesión y el ejemplo no
  lo comparaba nunca. Nuevos `autorizar(ctx)` e `intercambiar(ctx)`: guardan el verificador de
  PKCE y el estado en la sesión, los comparan en tiempo constante y los borran antes de canjear,
  así que el retorno vale una vez y solo para la sesión que lo pidió.

- **`Json` sin tope de anidamiento.** Un cuerpo de 50 KB de `[[[[…` agotaba la pila del hilo.
  Tope de 64 niveles.

- **`/corvo/listo` publicaba el mensaje del fallo**, que en un fallo de conexión lleva el host y
  el puerto de la base de datos. Nuevo `Health.checks().publico()`: el código y el estado, nada
  más.

- **Un separador codificado en la ruta se decodificaba en silencio.** `/a%2Fb` se convertía en
  `/a/b` *después* de enrutar, así que un filtro por prefijo veía una cosa y el sistema de
  archivos otra. Ahora da 400.

- **UTF-8 mal formado en un marco de texto** se sustituía en silencio en vez de cerrar con 1007,
  y las longitudes de marco no exigían la forma más corta. Los dos según RFC 6455.

### Trazado distribuido

**`Trace` — W3C Trace Context.** La cabecera `traceparent` entra, se hereda y sale:

```java
Cero.app().use(Trace.middleware()).start();
```

Desde ahí el identificador **aparece solo** en cada línea de `Log`, en el log de acceso y en las
llamadas salientes de `Http`. Ese es el punto: un trazado que hay que pasar a mano de método en
método se pierde en el primer método que no lo pasa, y entonces no sirve para nada.

La respuesta lleva `Trace-Id`, que convierte «me dio error» en «me dio error, aquí tienes el
número». `Trace.middlewareCallado()` lo omite si el servicio da a internet.

Una cabecera inválida no tumba la petición: se descarta y se empieza traza propia — un proxy mal
configurado no debe convertirse en una caída. Se validan versión, longitudes, hex en minúscula y
los identificadores todo a ceros, con 10 vectores.

**No trae exportador de tramos ni mide duraciones por operación.** Para eso está un agente de
OpenTelemetry, que no es asunto de un framework sin dependencias. Cero pone lo que solo Cero
puede poner: que el identificador entre, viaje y salga.

### Arreglado por el camino

- La versión que se escribía en el pom de cada proyecto nuevo estaba clavada en una constante y
  se quedaba atrás en cada subida. Ahora sale del pom, filtrada por Maven, y no hay un segundo
  número que acordarse de cambiar.
- La prueba del log de acceso leía la línea sin esperarla y fallaba de vez en cuando con la
  máquina cargada: el cliente puede volver antes de que el hilo que atendió acabe de desenrollar
  la cadena.

### Estado

**1 830 aserciones en verde**, ocho módulos, sin fallos. Eran 1 317 en 0.4.0.

---

## 0.4.0 · sin publicar

**LuxCore pasa a llamarse Corvo.** El nombre chocaba con un framework PHP del mismo entorno y se
confundían constantemente. *Corvo* significa cuervo —la marca que el proyecto ya llevaba— y
conserva el «cor» de LuxCore, que en latín es corazón, la misma raíz de *core*.

Es un cambio rompiente, y por eso va en una versión propia. Las anteriores no se tocan: `0.2.0` y
`0.3.0` siguen siendo exactamente lo que eran, y las aplicaciones que las usan no se enteran.

> **Esta versión nunca se publicó.** El renombrado a Corvo se hizo y se probó, pero antes de
> etiquetarla el nombre volvió a cambiar —ver [0.5.0](#050--sin-publicar)—. Se conserva la
> entrada porque el trabajo existió y porque el guion de migración sigue aceptando este nombre:
> hay aplicaciones que se quedaron aquí.

### Qué cambió

- Paquetes `lux.*` → `corvo.*`. Clases `Lux` → `Corvo` y `LuxServlet` → `CorvoServlet`.
- Coordenadas `lux:lux-*` → `dev.ginit.corvo:corvo-*`. El `groupId` pasa a ser un dominio propio,
  que es lo que exige Maven Central y antes no cumplíamos.
- La orden `./lux` pasa a `./corvo`.
- Configuración: `lux.*` → `corvo.*`, `LUX_*` → `CORVO_*`.
- Cookie de sesión `LUXSESSION` → `CORVOSESSION`.
- Métricas `lux_*` → `corvo_*`, y los endpoints `/lux/metrics` → `/corvo/metrics`.
- Tablas por defecto `lux_migraciones` y `lux_sesiones` → `corvo_*`.

### Añadido

- **Respuestas binarias.** `Result.bytes(datos, tipo)` y `Result.download(datos, nombre, tipo)`
  devuelven bytes tal cual desde un controlador: un PDF, una imagen, un zip armado al vuelo.

  Antes no había forma de hacerlo sin bajar a `Response.send(byte[])` a mano, y el atajo obvio
  —devolver el binario como `String`— lo rompe en silencio: el cuerpo se escribe en UTF-8 y todo
  byte por encima de `0x7F` sale convertido en otra cosa. El archivo llega, pesa parecido y no
  abre. La prueba manda los 256 valores de un byte y los compara uno a uno, porque un «hola» en
  ASCII habría pasado sin enterarse de nada.

  `download` además sanea el nombre del archivo antes de meterlo en `Content-Disposition`: unas
  comillas o un salto de línea en un nombre que venga de fuera partirían la respuesta en dos.

### Arreglado por el camino

- `Registry.get` resolvía dentro de `computeIfAbsent` y `build()` vuelve a entrar en `get()`, así
  que las cadenas de servicios hondas lanzaban `IllegalStateException: Recursive update`.
- `Config` recortaba el prefijo de las variables de entorno con la longitud escrita a mano. Al
  pasar de `LUX_` (4) a `CORVO_` (6), `CORVO_SERVER_PORT` se leía como `o.server.port` y la
  aplicación arrancaba con los valores por defecto en silencio. Ese camino no lo cubría ninguna
  prueba; ahora sí.

---

## 0.3.0 · 4 de agosto de 2026

Cierra la **fase 2** y recoge la auditoría externa completa. Es la primera versión que se
recomienda usar.

### Seguridad

| | |
|---|---|
| **Fijación de sesión** | `Session.regenerateId()` cambia el identificador conservando el contenido —token CSRF incluido—, y la cookie se reemite. Hay que llamarlo al identificarse. Antes el identificador de antes de entrar seguía valiendo después |
| **Agotamiento de memoria por estáticos** | el caché de recursos del classpath guardaba las ausencias, con la ruta pedida como clave: cada 404 dejaba dos entradas permanentes en un mapa sin tope. Un bucle sin autenticar llenaba el montón |
| **Limitador esquivable** | la ruta entraba en la clave, así que repartiendo la carga entre URLs se multiplicaba la cuota, y el mapa crecía con cada ruta inventada |
| **Enlaces simbólicos** | `readAttributes` los seguía aunque la línea de al lado declarara lo contrario |
| **Caducidad absoluta** | `sessionMaxLifetime(Duration)`. Antes solo contaba la inactividad: una sesión tocada de vez en cuando vivía para siempre |
| **CSRF** | `exempt` casaba por prefijo pelado: eximir `/api/publico` eximía `/api/publicoSECRETO` |

### Nuevo

- **`Controller`** — base **opcional** para controladores, con `param`, `query`, `form`, `session`,
  `usuario`, `view`, `json`, `redirect`, `fallo`… Quien no hereda sigue recibiendo el `Context`
  por parámetro. No guarda la petición en un campo: hay una instancia y un hilo virtual por
  conexión, así que sería una carrera.
- **`JdbcSessions`** — sesiones en tabla, para que sobrevivan al despliegue y las compartan varias
  instancias. Con `UPDATE` condicional: dos peticiones simultáneas no se pisan.
- **`ServerOptions.trustProxy(...)`** — `X-Forwarded-For` solo desde los proxies declarados. Sin
  esto, detrás de un proxy el limitador cuenta a todos juntos y el primero que llegue al tope
  bloquea el sitio.
- **`StaticFiles.cacheControl(...)`** — antes se emitían `ETag` y `Last-Modified` pero ninguna
  directiva de caché.
- **`Tx.setRollbackOnly()`** — una transacción anidada que falla condena a la externa. Antes, si
  quien la llamaba capturaba la excepción, la externa confirmaba con el trabajo a medias dentro.

### Corregido

- Un `Range` de más de 1 MB anunciaba en `Content-Range` un intervalo que el cuerpo no traía.
  Rompía vídeos y PDF grandes.
- Dos peticiones sobre la misma sesión se pisaban los datos, en silencio.
- El pool devolvía conexiones sin sanear: con una transacción abierta o `autoCommit` en `false`.
- Una carpeta del classpath se servía con 200 y el cuerpo vacío en vez de su `index.html`. Es lo
  que necesita una exportación estática, donde cada sección es una carpeta.
- `Jobs` pasa a llamarse **`Tasks`**.

### Renombrados

`Jobs` → `Tasks`. Sin alias: la clase nació esta misma semana y nunca se publicó.

### Añadido después de etiquetar la 0.3.0

Lo que sigue entró tras la etiqueta y saldrá en la **0.4.0**; el paquete `0.3.0` publicado no lo
lleva:

- **`Migrations`** — migraciones de esquema. Aplica los `.sql` de un directorio en orden y una
  sola vez, cada uno en su transacción, y **falla si un archivo ya aplicado cambió de huella**.
- **`Mail`** — cliente SMTP sobre `Socket`, con STARTTLS y AUTH LOGIN. JavaMail no es parte de
  Java SE, así que mandar un correo obligaba a traerse una dependencia.
- **`Sse`** — eventos del servidor al navegador. El panel del sitio ya no pregunta cada dos
  segundos: el servidor empuja.
- **`StaticFiles.spa()`** — respaldo para React, Svelte y Vue con rutas de cliente.
- **`lux new … --front`** — genera el proyecto separado en `backend/` y `frontend/`, con CORS de
  desarrollo y el respaldo de SPA ya puestos.

### Cifras

| | 0.2.0 | 0.3.0 |
|---|---|---|
| Pruebas | 1 262 | **1 292** |
| Núcleo (cuatro módulos) | 308 KB | **317 KB** |
| Clases de producción | 145 | **149** |

---

## 0.2.0 · 2 de agosto de 2026

Primera versión desplegada. Cierra la migración del núcleo heredado: servidor HTTP/1.1 propio con
un hilo virtual por conexión, router, pipeline, inyección, JSON, plantillas, capa de datos,
WebSocket, TLS recargable, instalador de una orden para los tres sistemas.

**No usar.** Su contenido cambió varias veces bajo el mismo número, y arrastra los defectos que
corrige la 0.3.0 — dos de ellos explotables desde fuera sin credenciales.
