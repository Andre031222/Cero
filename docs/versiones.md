# Versiones

Cada versión publicada es **inmutable**: mismo número, mismo `sha256`, siempre. Si el contenido
cambia, cambia el número.

Eso no fue así al principio, y conviene decirlo: durante el 3 de agosto de 2026 el paquete
`luxcore-0.2.0.tar.gz` se rehizo unas ocho veces sin cambiar de número. Quien instalara por la
mañana y quien instalara por la noche tenían frameworks distintos y ninguna forma de saberlo. Lo
señaló la auditoría del portal FINESI, y de ahí sale esta regla.

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
