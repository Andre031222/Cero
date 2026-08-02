# Endpoint `/db` — benchmark con lógica + base de datos real

Este endpoint responde a la crítica de revisión de que `/plaintext` y `/json`
sólo miden overhead de routing. `/db` ejecuta, **en cada request**, un
`SELECT id,title,body FROM note WHERE id=?` sobre una base de datos **H2
in-memory** sembrada con 1000 filas, y serializa el resultado a JSON.

## Por qué es justo y reproducible

La clase `Db.java` es **byte-idéntica en los cinco frameworks**
(JxMVC, Spring Boot, Quarkus, Micronaut, Javalin): mismo motor (H2), mismo
pool (`org.h2.jdbcx.JdbcConnectionPool`), misma query, mismo seed de 1000
filas, acceso por JDBC plano. Así, la diferencia medida sigue siendo el
**overhead de request-handling de cada framework**, ahora con un round-trip
de SQL real en la ruta, y no una diferencia entre ORMs o serializadores.

- Datos idénticos → la comparación no se contamina con elecciones de ORM.
- H2 in-memory → sin contenedor de DB externo; el harness sigue siendo de
  un solo comando y totalmente reproducible.
- Añade una sola dependencia (el driver H2) a las apps de benchmark, algo
  que el propio paper contempla ("the only additional production
  requirement is the JDBC driver of the target database").

La lógica compartida se validó de forma aislada: **160 000 llamadas
concurrentes (32 hilos × 5000), JSON válido, 0 errores** (thread-safe vía el
pool de H2).

## Cómo correrlo (en el mismo bare-metal Arch que el resto)

Desde `benchmarks/docker`, con el **mismo protocolo de aislamiento** que ya
usas (cores fijados, governor `performance`, Turbo off — el harness aplica
`--cpus`/`--cpuset`/`taskset`):

```bash
cd benchmarks/docker
BENCH_DB=1 ./bench.sh
```

`BENCH_DB=1` **añade** el endpoint `/db` a los ya existentes
(`plaintext`, `json`); sin esa variable, el run por defecto no cambia.

## Salida

`results/RESULTS-docker.md` incluirá ahora una columna extra:

```
| Framework | Imagen (MB) | Arranque (ms) | RSS (MB) | rps /plaintext | rps /json | rps /db |
```

y `results/raw-docker.csv` tendrá filas con `endpoint=.../db`.

## Qué hacer con los resultados

Pásame:
1. La columna `rps /db` por framework (o el bloque de la tabla), y
2. idealmente las filas `/db` crudas de `raw-docker.csv` (para latencia
   media y p99, igual que la Tabla 4 del paper).

Con eso **agrego una fila/columna a las Tablas 3 y 4** del paper y muevo el
endpoint de "future work" a resultado medido, cerrando el talón de Aquiles
que señaló la revisión.

## Notas

- La referencia **GraalVM native** (`bench-native.sh`) **no** incluye `/db`:
  H2 requiere configuración de reflexión para native-image, y la
  comparación de interés (`/db`) es en **modo JVM**, que es donde están los
  cinco frameworks. Se puede añadir después si se desea.
- Spring Boot desactiva su auto-configuración de datasource
  (`exclude = DataSourceAutoConfiguration.class`) para que H2 sea sólo una
  librería JDBC y no un datasource gestionado por Spring — así la
  comparación no premia ni castiga a Spring por su auto-config.
