# Mediciones locales de lux-http

Última medición: 1 de agosto de 2026, tras cerrar las fases A–D.

> **Este documento ya no es la fuente de los números de rendimiento.** El 2 de agosto de 2026 se
> corrió el harness comparativo con LuxCore dentro, y lo que salió de ahí manda:
> [`benchmarks/results/RESULTS-docker.md`](../benchmarks/results/RESULTS-docker.md). La corrida
> **desmiente** el rps de esta página: en condiciones iguales para los seis contendientes, LuxCore
> queda en mitad de tabla en throughput, no 2,2× por delante. Lo de abajo se conserva como
> registro de cómo se llegó hasta aquí, y como recordatorio de lo fácil que es engañarse midiendo
> en casa.

**No son comparables con la tabla de `benchmarks/results/`** y no deben citarse como si lo fueran:
aquella se midió en Docker con límites fijos e idénticos para todos los frameworks. Esto es un
MacBook con el cliente en la misma máquina y sin rival contra el que comparar. Sirven como señal,
no como resultado.

## Entorno

- macOS (Darwin 25.5.0), OpenJDK 25.0.3
- Servidor y cliente en el mismo host, sin aislamiento de cores
- Handler: `res.text("Hello, World!")`
- Carga: `ab -k -n 100000 -c 64`

## Resultados

| Métrica | lux-http inicial | lux-http completo | Con lux-core | Meta | JxMVC/Tomcat |
|---|---|---|---|---|---|
| Arranque | 17 ms | 28 ms | **51 ms** | < 150 ms | 1392 ms |
| Artefacto | 36 KB | 64 KB | **123 KB** | ≤ 400 KB | 253 KB + Tomcat |
| rps | 107 779 | 100 554 | **94 610** | ≥ 48 000 | 43 691 |
| Pruebas | 30 | 144 | **297** | — | 347 |

Sin fallos en ninguna corrida. El `ab` de `lux-http` fueron 100 000 peticiones (99 936 sobre
keep-alive reutilizado); el de LuxCore completo, 50 000.

Los 51 ms del framework completo incluyen carga de clases, escaneo de anotaciones de los
controladores y arranque del servidor: el servidor solo tarda 19 ms, el resto es la JVM.

El coste de ir sumando capas ha sido: **+11 ms y +27 KB** por TLS, sesiones, multipart, gzip y
estáticos; **+23 ms y +59 KB** por el router, la inyección de dependencias y el JSON. El rps baja
un 12 % desde el servidor pelado hasta el framework completo — lo que cuesta enrutar, resolver
parámetros por reflexión y serializar.

## Lo que estos números no dicen

- **El rps no es comparable.** Cliente y servidor comparten CPU, y `ab` es un generador de carga
  distinto del que usó el paper. El número real sale del harness de `benchmarks/docker`, con
  LuxCore como sexto contendiente, en el mismo Arch bare-metal.
- **El RSS no se midió en serio.** El proceso llegó a 314 MB tras la carga, pero sin límite de
  heap la JVM reserva según la RAM de la máquina. Sin `-m 2g` el dato no significa nada.
- **El handler no hace trabajo.** Devuelve una constante. El endpoint `/db` del harness es el que
  mide el framework haciendo trabajo de aplicación.

Lo que sí es sólido y no depende del entorno: **51 ms de arranque y 123 KB de artefacto**, contra
1392 ms y 253 KB más un Tomcat de ~15 MB.

## Cómo reproducir

```bash
cd java && mvn -DskipTests package
# servidor mínimo contra lux-http/target/lux-http-0.1.0.jar
ab -k -n 100000 -c 64 http://127.0.0.1:8099/
```
