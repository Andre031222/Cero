# Cero — Harness de benchmarks reproducible

Protocolo y herramientas para medir **Cero** frente a **Spring Boot, Quarkus, Micronaut y
Javalin** de forma reproducible. Diseñado para respaldar cifras publicables (tamaño, arranque,
memoria, throughput y latencia) con una metodología descrita y sin herramientas externas de
carga: el generador está en JDK puro.

> Estado: harness completo y ejecutable. Las filas de resultados se completan ejecutando el
> protocolo en una **máquina limpia y aislada** (ver §6). No publiques números de un portátil
> de desarrollo con IDE o navegador abiertos.

---

## 1. Qué se mide

| Métrica | Definición | Cómo |
|---|---|---|
| **Tamaño desplegable** | Bytes del artefacto que se despliega, **incluyendo el servidor** | `docker/bench.sh` |
| **Dependencias runtime** | Nº de JARs de terceros en el classpath de ejecución | conteo del árbol de dependencias |
| **Arranque en frío** | ms desde lanzar el proceso hasta la 1ª respuesta 200 | `docker/bench.sh` |
| **Memoria (RSS)** | RSS tras warmup en estado estacionario | `docker/bench.sh` |
| **Throughput** | req/s sostenidos, ventana de medición tras warmup | `load/LoadClient` |
| **Latencia** | media y p50/p90/p95/p99 (ms) bajo la misma carga | `load/LoadClient` |

Endpoints canónicos, idénticos en todos los frameworks:

- `GET /plaintext` → `text/plain` con el cuerpo `OK`.
- `GET /json` → `application/json` con `{"message":"hello","n":42}`.
- `GET /db` → una consulta contra el mismo motor (H2) en todos.

## 2. Por qué el tamaño se compara de una sola forma

Cero trae su propio servidor HTTP: el artefacto que se despliega ya incluye todo lo necesario
para atender peticiones. Los uber-JAR de Spring Boot, Quarkus, Micronaut y Javalin también.
La comparación es por tanto directa —**desplegable contra desplegable**— y no necesita la doble
contabilidad que hace falta cuando un framework deja el servidor fuera del artefacto como
dependencia `provided`.

## 3. Entorno (rellenar al ejecutar)

```
CPU:            <modelo, núcleos/hilos>
RAM:            <GB>
SO:             <distro/versión, kernel>
JDK:            <vendor + versión>   (mismo para todos)
Aislamiento:    sin GUI, sin otros servicios; governor=performance
Cliente/carga:  misma máquina (loopback) o máquina dedicada en LAN (preferido)
Repeticiones:   N=5 corridas por métrica; se reporta mediana + [min,max]
Warmup:         5 s (carga) / descartar 1ª corrida (arranque)
```

## 4. Herramienta de carga (JDK puro, sin dependencias)

```bash
cd load && javac LoadClient.java
java LoadClient <url> <conexiones> <segundos> [warmupSegs]
java LoadClient http://localhost:8080/plaintext 64 30 5
```

Imprime una línea CSV: `url,conns,durSecs,requests,errors,non2xx,rps,meanMs,p50,p90,p95,p99`.

## 5. Apps de referencia

Las apps mínimas equivalentes, con sus versiones fijadas y sus Dockerfile, están en
[`docker/apps/`](docker/apps/) y listadas en [`docker/README.md`](docker/README.md). Las cinco
exponen `/plaintext`, `/json` y `/db` para que la comparación sea uno a uno.

## 6. Protocolo de ejecución

Un solo comando: construye las cinco imágenes, las mide una por una y escribe la tabla.

```bash
cd benchmarks/docker
BENCH_DB=1 ./bench.sh 64 30 5        # 64 conexiones, 30 s, 5 repeticiones
./bench.sh --tabla                   # rehacer la tabla sin volver a medir
```

Los guiones sueltos que había antes —medir arranque, memoria, tamaño y carga por separado, a
mano y por framework— se retiraron: `bench.sh` hace las cuatro cosas en condiciones idénticas
para todos, que era justo lo que a mano no se podía garantizar.

Para la carga larga, que es otra pregunta distinta:

```bash
./carga-sostenida.sh 30 64           # 30 minutos vigilando RSS y descriptores
```

Barridos recomendados de concurrencia: `1, 8, 32, 64, 128, 256` conexiones (curva de
throughput/latencia), 30 s por punto, 5 s de warmup.

## 7. Resultados

Se consolidan en [`results/`](results/LEEME.md), con el CSV crudo por corrida al lado. Una tabla sin su CSV no es un resultado: es un recuerdo.

## 8. Amenazas a la validez

- **Steady-state del JIT**: sin warmup, HotSpot penaliza a la JVM; por eso se descarta el warmup.
- **Carga en loopback**: satura el mismo host; para números finales, cliente en máquina aparte.
- **AOT vs JIT**: Quarkus y Micronaut pueden compilarse a nativo (GraalVM), lo que cambia
  radicalmente arranque y memoria; indicar el modo (JVM o nativo) de cada corrida.
- **Paridad de endpoints**: los tres endpoints deben ser idénticos en semántica y salida.
