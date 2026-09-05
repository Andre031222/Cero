# ¿Está listo para producción?

**No todavía.** Y conviene decirlo con precisión, porque «no» a secas no ayuda: falta poco en
funcionalidad y bastante en confianza.

## Lo que sí está resuelto

- **1 830 aserciones en verde**, cero dependencias en el núcleo verificadas en cada corrida
  de CI. Esa cifra es la de la corrida con PostgreSQL y MySQL levantados, que es la de CI.
  Sin ellos, `MotorTests` se omite —y lo dice— y salen 1 624: si mides en local y no
  cuadra, es por eso.
- Arranca en ~100 ms dentro de un contenedor y pesa 407 KB. El resto de contendientes, medidos
  a la vez y en las mismas condiciones, tardan entre 4 y 13 veces más.
- El endurecimiento HTTP es explícito y está probado: rechaza cabeceras plegadas,
  `Content-Length` duplicado, `Content-Length` junto a `Transfer-Encoding`, `Host` ausente o
  duplicado, caracteres de control en las cabeceras de respuesta y path traversal en estáticos.
- TLS, techo de conexiones, timeout de handler y apagado ordenado están puestos.
- CORS, CSRF, rate limiting, validación, sanitizado y cabeceras de seguridad están puestos.
- Observabilidad mínima: métricas por ruta con percentiles, log y log de acceso.
- Autenticación: OAuth 2.0 / OIDC con PKCE y verificación real de firma, y PBKDF2 para
  contraseñas.
- **Corre en Linux en cada push**, con PostgreSQL y MySQL levantados, sobre JDK 25.

## Lo que falta para poder decir que sí

Ordenado por lo que más pesa.

### 1. Nadie lo ha puesto en producción todavía

Ya hay una aplicación completa encima —`java/ejemplo`, con vistas, formularios, CSRF, validación,
base de datos y API REST, cubierta por 43 pruebas de punta a punta— y destapó cuatro huecos que la
lista de casillas no veía. Eso convierte «paridad» en algo verificado, no declarado.

Lo que sigue sin ocurrir es tráfico real: nadie ha desplegado esto y lo ha dejado correr semanas.
Y el sitio de referencia ya no depende de Tomcat: es `cero-web`.

**Criterio:** una aplicación pequeña en producción de verdad, con tráfico real, durante semanas.

### 2. HTTP/2 · **hecho**

Las tres puertas están abiertas: conocimiento previo, `Upgrade: h2c` y **ALPN sobre TLS**. Esa
última es la que importa — ningún navegador habla h2 en claro, así que sin ALPN el módulo entero
no llegaba a un navegador nunca. `curl` negocia h2 sobre https contra el servidor y responde.

Están la capa de tramas, HPACK completo, multiplexación de verdad —24 peticiones de 120 ms en
129 ms sobre una conexión—, control de flujo por conexión y por flujo, respuestas por `stream()`
saliendo en tramas según se escriben, CONTINUATION, trailers, SETTINGS negociados, PING,
RST_STREAM y GOAWAY con el código exacto que pide el RFC en cada caso.

Se verifica con **32 vectores del apéndice C del RFC 7541** para HPACK y **39 requisitos
numerados** en [spec/http2.md](../spec/http2.md), separando los que rompen la conexión de los que
solo cortan un flujo — confundirlos convierte una petición mal formada en una caída de todo lo que
ese cliente tuviera en vuelo.

Entre ellos, **las cuatro inundaciones conocidas del protocolo**: CONTINUATION sin fin
(CVE-2024-27316), Rapid Reset (CVE-2023-44487), amplificación por tramas de control y bombas de
expansión en HPACK. Ninguna es una entrada malformada —cada trama es válida por separado— así que
no las caza ningún control de sintaxis: hacen falta topes explícitos, y aquí están puestos y
probados.

**Lo que sigue faltando** está escrito allí: prioridad de escritura entre flujos,
`SETTINGS_MAX_HEADER_LIST_SIZE` y vectores de expansión en HPACK. Nada de eso impide usarlo.

Con esto, **«sin contenedor» deja de llevar nota al pie**: el proxy delante ya es una elección de
despliegue, no un requisito para que un navegador hable con Cero.

### 3. El parser HTTP tiene dos días

Tomcat lleva 25 años recibiendo tráfico hostil y tiene un historial de CVEs ya corregidos que
nosotros vamos a redescubrir por nuestra cuenta. Que la auditoría encontrara una inyección CRLF a
las pocas horas de escribir el módulo dice que el riesgo es real, no teórico.

**Hecho en parte:** hay fuzzing dirigido y **23 vectores de conformidad con RFC 9112** — forma
del destino, reglas de cabecera, longitud del cuerpo, orden de las codificaciones. Escribirlos
destapó cuatro incumplimientos reales: no se aceptaba *absolute-form* ni *asterisk-form*, se
tragaba `chunked` fuera de la última posición y se admitían bytes nulos en valores de cabecera.
Los cuatro corregidos.

**Lo que falta:** una suite reconocida de terceros. No existe libre para HTTP/1.1, así que la
alternativa realista es ampliar los vectores propios.

### 4. `cero-data` contra motores reales · **hecho**

Los 47 casos de `MotorTests` corren contra **H2, PostgreSQL 16 y MySQL 8** en Docker: claves
generadas, decimales exactos, booleanos, fechas, paginación con `LIMIT`/`OFFSET`, alias en
mayúsculas, transacciones confirmadas y deshechas, repositorios e inserción por lotes. Los tres
motores pasan la misma batería.

Encontró un fallo real: `Row.as()` ignoraba `@Column`, así que un record anotado devolvía ese
campo en `null` — mientras que `Repository`, que sí lo respetaba, funcionaba. Dos caminos que
debían coincidir y no coincidían. Corregido haciendo que ambos usen el mismo resolutor de nombres.

**Pendiente aquí:** SQL Server y Oracle, y el comportamiento ante caída y reconexión del motor.

### 5. Los números de rendimiento · **medidos, y no dicen lo que se creía**

Los 94 610 rps de la medición casera no valían: cliente y servidor en la misma máquina, con `ab`,
sin aislamiento. Ya están sustituidos por una corrida del harness con Cero como sexto
contendiente, `/plaintext`, `/json` y `/db`, 5 repeticiones de 30 s y cero errores
([tabla](../benchmarks/results/RESULTS-docker.md)).

Lo que sale de ahí, sin adornos:

- **Arranque: 106 ms.** El siguiente es Javalin con 451: una ventaja de categoría, no de matiz.
- **Memoria: 136,4 MB de RSS, la más baja de las seis.** Micronaut, el segundo, gasta 201.
- **Throughput: primero en los tres endpoints.** `/plaintext` 26 425, `/json` 25 431 y `/db`
  25 931. En `/db` —el que mide el framework haciendo trabajo de verdad— saca un 38 % a JxMVC.
- **Imagen: 110,3 MB**, segunda por 0,2 MB detrás de JxMVC. La base JRE domina el tamaño.

**Criterio que sigue abierto:** repetir la corrida en el mismo Arch bare-metal que usó el paper.
Lo medido es en Docker Desktop: los números relativos son justos, los absolutos no son citables.

**Sobre el RSS:** era el punto flojo —298 MB— y resultó ser un fallo, no un peso. El vigilante
programaba una tarea por petición que al cancelarse no salía de la cola; con eso corregido bajó a
136,4 MB. Sigue por encima de la meta absoluta de 120 MB, pero ya es el más bajo de los seis.

### 6. Las pruebas adversariales son ráfagas, no maratones · **parcialmente hecho**

`HostileTests` ya cubre lo que faltaba en ráfaga: conexiones lentas que caducan, 20 sockets mudos
a la vez, `Content-Length` que miente, un chunk de 4 GB declarado, 15 clientes cortando a media
respuesta, 1000 peticiones simultáneas comprobando que ninguna respuesta se mezcla con otra, y 24
entradas malformadas —bytes nulos, `Content-Length` negativo y de 20 dígitos, 500 cabeceras, ruta
de 20 KB, `Host` de 50 KB— sin una sola excepción ni 5xx inesperado.

Lo que sigue faltando es el eje del **tiempo**: nada corre más de unos segundos, así que una fuga
de memoria o de descriptores no tendría cómo aparecer.

**Hecho en parte.** `benchmarks/carga-sostenida.sh` mantiene tráfico continuo y muestrea RSS,
descriptores e hilos. Una corrida de 30 minutos con 64 conexiones: RSS de 382,6 a **374,4 MB**
—bajó—, descriptores clavados en 99 e hilos en 44. Sin fuga.

**Lo que falta:** días, no minutos, y con conexiones hostiles mezcladas.

### 7. Huecos operativos conocidos

| Hueco | Impacto |
|---|---|
| Sin auditoría de accesos | hay log de acceso, pero no un rastro inmutable de quién hizo qué |
| Sin HTTP/2 | lo absorbe el proxy inverso que igualmente conviene poner delante |

Se han ido de esta lista, y con prueba cada uno: log de acceso, métricas, WebSocket, caché, bus
de eventos, **rangos en estáticos**, **recarga de certificado TLS sin reiniciar** y **almacén de
sesiones compartido** para varias instancias.

## Cómo llegar

En orden, porque cada paso informa al siguiente. Tachado lo que ya está:

1. ~~Migrar métricas y logger~~ — hecho, con log de acceso incluido.
2. ~~Correr `cero-data` contra PostgreSQL y MySQL reales~~ — hecho, y encontró un fallo.
3. ~~Fuzzing dirigido del parser~~ — hecho; falta el banco de **conformidad** HTTP/1.1.
4. ~~Correr la suite en Linux~~ — hecho, en cada push, sobre JDK 21 y 25.
5. ~~Levantar el sitio de referencia en standalone~~ — hecho: es `cero-web`.
6. Benchmark en Arch bare-metal, con los números buenos.
7. Prueba de carga sostenida con conexiones hostiles.
8. Elegir **una** app pequeña y de bajo riesgo, ponerla en producción detrás de un proxy inverso,
   y dejarla correr semanas antes de mover nada importante.

## Una recomendación

Aunque todo lo anterior se cumpla, el primer despliegue debería ir **detrás de nginx o Caddy**, no
expuesto directo. El proxy absorbe TLS, slowloris, HTTP/2 y el log de acceso — que son justo los
cinco puntos donde Cero es más joven. Cuando lleve meses de tráfico real, se discute quitarlo.
