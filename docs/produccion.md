# ¿Está listo para producción?

**No todavía.** Y conviene decirlo con precisión, porque «no» a secas no ayuda: falta poco en
funcionalidad y bastante en confianza.

## Lo que sí está resuelto

- **601 pruebas en verde**, cero dependencias verificadas, ni una referencia a `jakarta.*`.
- Arranca en 51 ms y pesa 215 KB. Eso es real y no depende del entorno.
- El endurecimiento HTTP es explícito y está probado: rechaza cabeceras plegadas,
  `Content-Length` duplicado, `Content-Length` junto a `Transfer-Encoding`, `Host` ausente o
  duplicado, caracteres de control en las cabeceras de respuesta y path traversal en estáticos.
- TLS, techo de conexiones, timeout de handler y apagado ordenado están puestos.
- CORS, CSRF, rate limiting, validación y sanitizado están puestos.

## Lo que falta para poder decir que sí

Ordenado por lo que más pesa.

### 1. Nadie lo ha usado todavía

Ninguna aplicación real corre sobre LuxCore. `jxmvc2x` sigue en Tomcat y no se ha intentado
levantarlo en modo standalone. Hasta que una app completa funcione encima, «paridad» es una
afirmación sobre una lista de casillas, no sobre software.

**Criterio:** `jxmvc2x` corriendo entero con `Lux.run(...)`, sin Tomcat, sirviendo sus 17 vistas.

### 2. El parser HTTP tiene dos días

Tomcat lleva 25 años recibiendo tráfico hostil y tiene un historial de CVEs ya corregidos que
nosotros vamos a redescubrir por nuestra cuenta. Que la auditoría encontrara una inyección CRLF a
las pocas horas de escribir el módulo dice que el riesgo es real, no teórico.

**Criterio:** fuzzing del parser (líneas de petición, cabeceras, chunked, multipart) y una corrida
de un banco de conformidad HTTP/1.1.

### 3. `lux-data` contra motores reales · **hecho**

Los 47 casos de `MotorTests` corren contra **H2, PostgreSQL 16 y MySQL 8** en Docker: claves
generadas, decimales exactos, booleanos, fechas, paginación con `LIMIT`/`OFFSET`, alias en
mayúsculas, transacciones confirmadas y deshechas, repositorios e inserción por lotes. Los tres
motores pasan la misma batería.

Encontró un fallo real: `Row.as()` ignoraba `@Column`, así que un record anotado devolvía ese
campo en `null` — mientras que `Repository`, que sí lo respetaba, funcionaba. Dos caminos que
debían coincidir y no coincidían. Corregido haciendo que ambos usen el mismo resolutor de nombres.

**Pendiente aquí:** SQL Server y Oracle, y el comportamiento ante caída y reconexión del motor.

### 4. Los números de rendimiento no valen

94 610 rps se midió con cliente y servidor en la misma máquina, con `ab`, sin aislamiento de
cores. No es comparable con nada y no debe citarse.

**Criterio:** LuxCore como sexto contendiente en `benchmarks/docker`, en el mismo Arch bare-metal
que usó el paper, con `/plaintext`, `/json` y `/db`.

### 5. Las pruebas son funcionales, no adversariales

601 casos, pero ninguno de concurrencia real, sockets lentos (slowloris), cuerpos parciales,
clientes que abortan a media respuesta, ni memoria bajo carga sostenida.

**Criterio:** una prueba de carga de varias horas con conexiones hostiles, vigilando RSS y
descriptores de archivo.

### 6. Huecos operativos conocidos

| Hueco | Impacto |
|---|---|
| Sin log de acceso | no hay trazabilidad de peticiones |
| Sesiones por proceso | no se puede escalar a varias instancias sin sesión pegajosa |
| Sin recarga de certificado TLS | renovar el certificado obliga a reiniciar |
| Sin WebSocket | las apps que lo usan no pueden migrar todavía |
| Sin métricas | `JxMetrics` aún no está migrado |

## Cómo llegar

En orden, porque cada paso informa al siguiente:

1. Levantar `jxmvc2x` en standalone. Lo que se rompa dicta el resto.
2. Migrar métricas y logger — sin observabilidad no se opera nada.
3. Correr `lux-data` contra PostgreSQL y MySQL reales.
4. Fuzzing del parser y conformidad HTTP/1.1.
5. Benchmark en Arch bare-metal, con los números buenos.
6. Prueba de carga sostenida con conexiones hostiles.
7. Elegir **una** app pequeña y de bajo riesgo, ponerla en producción detrás de un proxy inverso,
   y dejarla correr semanas antes de mover nada importante.

## Una recomendación

Aunque todo lo anterior se cumpla, el primer despliegue debería ir **detrás de nginx o Caddy**, no
expuesto directo. El proxy absorbe TLS, slowloris, HTTP/2 y el log de acceso — que son justo los
cinco puntos donde LuxCore es más joven. Cuando lleve meses de tráfico real, se discute quitarlo.
