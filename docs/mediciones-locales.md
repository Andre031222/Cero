# Mediciones locales de lux-http

Primera medición del servidor, 1 de agosto de 2026. **No son comparables con la tabla de
`benchmarks/results/`** y no deben citarse como si lo fueran: aquella se midió en Arch bare-metal
y en Docker con 4 cpus, 2 GB y cliente aislado en cores separados. Esto es un MacBook con el
cliente en la misma máquina. Sirven como señal, no como resultado.

## Entorno

- macOS (Darwin 25.5.0), OpenJDK 25.0.3
- Servidor y cliente en el mismo host, sin aislamiento de cores
- Handler: `res.text("Hello, World!")`

## Resultados

| Métrica | Medido | Meta de fase 1 | JxMVC sobre Tomcat |
|---|---|---|---|
| Arranque | **17 ms** | < 150 ms | 1392 ms |
| JAR | **36 455 bytes** | ≤ 400 KB | 253 KB |
| Heap tras arrancar | **4 MB** | — | — |
| rps `/plaintext` | **107 779** | ≥ 48 000 | 43 691 |

`ab -k -n 100000 -c 64` — 100 000 peticiones completas, **0 fallidas**, 99 936 sobre conexiones
keep-alive reutilizadas.

## Lo que estos números no dicen

- **El rps no es comparable.** Cliente y servidor comparten CPU, y `ab` es un generador de carga
  distinto del que usó el paper. El número real sale del harness de `benchmarks/docker`, con
  LuxCore como sexto contendiente, en el mismo Arch bare-metal.
- **El RSS no se midió en serio.** El proceso llegó a 282 MB tras la carga, pero sin límite de
  heap la JVM reserva según la RAM de la máquina (460 GB aquí). Sin `-m 2g` el dato no significa
  nada.
- **El handler no hace trabajo.** Devuelve una constante. El endpoint `/db` del harness es el que
  mide el framework haciendo trabajo de aplicación.

Lo que sí es sólido y no depende del entorno: **17 ms de arranque y 36 KB de JAR**, contra 1392 ms
y 253 KB. Ahí no hay margen de interpretación.

## Cómo reproducir

```bash
cd java && mvn -DskipTests package
# servidor mínimo contra lux-http/target/lux-http-0.1.0.jar
ab -k -n 100000 -c 64 http://127.0.0.1:8099/
```
