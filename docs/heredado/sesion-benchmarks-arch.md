# Sesión de trabajo — Arch Linux (2026-07-05)

Bitácora de lo realizado en esta sesión: endurecer el benchmark, correr la
campaña oficial de publicación en bare-metal, arreglar el build nativo de
GraalVM, y redactar el paper para *Software: Practice and Experience* con la
plantilla oficial de Wiley.

**Máquina:** Intel Core i5-12500H (4 P-cores @2.5GHz + 8 E-cores @1.8GHz),
30 GiB RAM, kernel Linux 7.0.14 (Arch), OpenJDK 25, Docker 29.6.1.

---

## 1. Endurecimiento del benchmark (rigor de publicación)

**Commit `b2dce13`** — `benchmarks/docker/bench.sh` + `archlinuxbench.md`.

- La tabla ahora reporta **mediana de arranque y RSS** (antes tomaba la 1ª muestra), no solo de rps.
- Marca frameworks con errores/no-2xx con `⚠` y **sale con código 3** si hubo cualquier error.
- Nuevos env de reproducibilidad: `BENCH_CPUSET` (`--cpuset-cpus`) y `BENCH_CLIENT_CPUS` (`taskset`) para aislar el generador de carga del servidor.
- `archlinuxbench.md`: paso de validación de resultados, guía de aislamiento del cliente, turbo boost/throttling térmico, y corrección de la explicación warmup/mediana.

## 2. Corrida oficial + fix del build nativo

**Commit `b133891`** — resultados en `benchmarks/results/`.

**Diseño experimental (validez):**
- Contenedor fijado a 2 P-cores: `--cpuset-cpus=0-3 --cpus=4 --memory=2g`.
- Cliente `LoadClient` fijado a los otros 2 P-cores: `taskset -c 4-7` (disjuntos → cero contención).
- CPU governor `performance`, **Turbo Boost desactivado** (consistencia de frecuencia).
- Carga: `conns=64, dur=30s, reps=5` (mediana), warmup 5s, endpoints `/plaintext` y `/json`.

**Bug encontrado y arreglado (`Dockerfile.native`):** el `pom.xml` no define el
profile `native`, así que el flag `-Dnative` se ignoraba en silencio y Maven
hacía un package JVM normal (sin `*-runner`) → `cp target/*-runner` fallaba.
Fix: usar `-Dquarkus.native.enabled=true` (propiedad que el plugin sí lee) y
`-B` en vez de `-q`. Resultado: binario nativo de **72.8 MB, arranque 12 ms**.

**Resultado final — 6/6 frameworks, 10/10 repeticiones, 0 errores en 92.76M requests:**

| Framework | Imagen (MB) | Arranque (ms) | RSS (MB) | rps /plaintext | rps /json |
|---|---|---|---|---|---|
| **jxmvc** | 271.7 | 822 | 448.5 | 49 062 | 48 720 |
| spring | 299.9 | 1945 | 375.6 | 49 462 | 49 896 |
| quarkus | 295.9 | 606 | 431.2 | 55 087 | 54 209 |
| micronaut | 292.5 | 1154 | 331.4 | 50 974 | 50 652 |
| javalin | 286.4 | 369 | 424.6 | 54 169 | 53 614 |
| quarkus-native | 72.8 | 12 | 25.1 | 52 699 | 50 073 |

Artefactos: `RESULTS-docker.md` (tabla), `raw-docker.csv` (60 filas crudas),
`STATS.md` (mediana + [min–max]), `ENV.md` (entorno).

**Hallazgo honesto:** en bare-metal, JxMVC **no** supera a Spring/Micronaut en
throughput (queda ~1% bajo Spring, ~4% bajo Micronaut) — se corrigió el claim
previo del outline. Su fortaleza real: **imagen JVM más pequeña, arranque más
rápido que Spring/Micronaut, latencia p99 indistinguible y 0 errores**.

## 3. Paper para *Software: Practice and Experience*

**Plantilla OFICIAL de Wiley** (clase `USG`, New Journal Design), descargada de
`https://authors.wiley.com/asset/WileyDesign.zip`. No se versiona (copyright,
~7 MB); se descarga con las instrucciones de `paper/README.md`.

**Commits `93ea36e`, `1b857c6`, `48690a7`.** Ubicación: `paper/`.

```
paper/
├── main.tex                 documento principal (clase USG, metadata SPE)
├── build.sh                 compila → main.pdf (9 págs)
├── README.md · .gitignore
├── references.bib           bibliografía inicial (ampliar a 15–25 refs)
├── OUTLINE.md               outline + checklist (claim de throughput corregido)
├── sections/
│   ├── 01-introduction.tex
│   ├── 02-related.tex
│   ├── 03-design.tex        pipeline 15 etapas, subsistemas
│   ├── 04-implementation.tex rate-limiter, OIDC, tests
│   ├── 06-evaluation.tex    metodología + resultados + análisis por RQ
│   ├── 07-discussion.tex    threats + limitaciones honestas
│   └── 08-conclusion.tex
└── tables/  results.tex · latency.tex · qualitative.tex
```

**Todas las afirmaciones técnicas se verificaron contra el código** (no se
inventaron):

| Afirmación | Verificado en |
|---|---|
| JAR 253 KB, 0 deps runtime | `JxMVC.Core/pom.xml` (única dep `jakartaee-api` provided); jar = 258 897 bytes |
| Pipeline 15 etapas | `MainLxServlet.serviceInternal()` |
| Rate-limiter `prev·(1−t/W)+cur` | `JxRateLimiter.estimatedUsage()` |
| OIDC: verificación RS256 del id_token contra JWKS solo con `java.security` | `JxOAuth.verifyJwtClaims()` (+ tests: firma manipulada / alg:none rechazados) |
| 347 tests sin framework externo | se corrió `JxTestSuite` → `TOTAL pass=347 fail=0` |

## 4. Instalaciones (con sudo del usuario)

- `cpupower` (governor performance, referenciado en §8 de la guía).
- `texlive-plaingeneric` (faltaba `ulem.sty`, requerido por la clase de Wiley).
- Docker: daemon arrancado (`systemctl enable --now docker`); el usuario ya
  estaba en el grupo `docker`.
- **CPU restaurada** al finalizar: Turbo ON, governor `powersave`.

## 5. Pendientes (checklist del paper)

- [ ] Redactar Agradecimientos/financiación.
- [ ] Figura del pipeline (diagrama TikZ de las 15 etapas; hoy es lista).
- [ ] Ampliar related work a 15–25 referencias.
- [ ] Subir v3.4.0 a Zenodo → DOI; enlazar en el paper.
- [ ] Acordar autoría/afiliación y datos de correspondencia.
- [ ] (Opcional) barrido de concurrencia para la curva throughput/latencia.

## 6. Commits de la sesión

```
48690a7 paper: redacta secciones 1-4 y 7-8 (prosa completa, datos verificados)
1b857c6 paper: esqueleto main.tex con plantilla OFICIAL de Wiley (clase USG)
93ea36e paper: seccion de Evaluacion en LaTeX con cifras oficiales (SPE)
b133891 bench: corrida oficial de publicación (6 frameworks, cero errores) + fix nativo
b2dce13 bench: rigor de publicación — mediana arranque/RSS, validación de errores
```

## 7. Cómo reproducir

```bash
# Benchmark oficial (requiere Docker + JDK en el host):
cd benchmarks/docker
BENCH_CPUS=4 BENCH_CPUSET=0-3 BENCH_CLIENT_CPUS=4-7 BENCH_MEM=2g BENCH_NATIVE=1 \
  ./bench.sh 64 30 5

# Paper (requiere TeX Live + plantilla de Wiley, ver paper/README.md):
cd paper && ./build.sh   # genera main.pdf
```

> Nota de seguridad: durante la sesión se usó la contraseña de sudo en el chat.
> Como era temporal, conviene rotarla.
