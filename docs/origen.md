# Origen de este repositorio

LuxCore parte del código de **JxMVC 3.4.0**, copiado el **1 de agosto de 2026**.

## De dónde viene

| | |
|---|---|
| Origen | `smb://TUF.local/Research-Dev/AUR_Software/19.Soft_JXMVC` |
| Repositorio del origen | `github.com/Andre031222/19.Soft_JXMVC` |
| Commit de referencia | `eac354e` — *docs: agrega licencia MIT* |
| Estado del origen al copiar | 15 archivos modificados y 7 sin rastrear (el trabajo del endpoint `/db`), copiados tal como estaban |

La copia se hizo con:

```bash
rsync -a --exclude '.git' --exclude 'target/' --exclude '.DS_Store' \
  <origen>/ LuxCore/
```

Verificada con `diff -r`: **94 archivos `.java`** en ambos lados, sin diferencias de contenido.
Lo único que no vino: los tres `.DS_Store` (excluidos) y `.claude/skills/research-paper-writing/`
(directorio protegido por permisos de macOS sobre el recurso SMB; es una skill de asistente,
no código del framework).

## Por qué se copió en vez de trabajar sobre el original

**`19.Soft_JXMVC` está congelado.** Alimenta un artículo en revisión para *Software: Practice and
Experience*, cuya carpeta es `AUP_Papers/13.-JxMVC_SPE/`. Los benchmarks del artículo se midieron
en Arch bare-metal bajo condiciones controladas; cualquier cambio en el framework invalida esos
números. El origen tiene además tres pendientes abiertos (benchmark `/db` en Arch, DOI de Zenodo,
autoría/ORCID) que siguen su curso sin relación con este repositorio.

Tampoco se copió el `.git` del origen: su `origin` apunta al repositorio del artículo, y bastaría
un `git push` distraído desde aquí para reescribirlo. Este repositorio arranca con historial propio.

**Regla de trabajo: `19.Soft_JXMVC` es de solo lectura.** Nada de lo que ocurra en LuxCore se
propaga hacia allá.

## Qué se hereda y qué cambia

Se hereda el núcleo: 54 clases en `jxmvc/core/`, de las cuales **40 no importan nada de
`jakarta.*`** y se mudan sin tocarse. Se hereda también el banco de pruebas de rendimiento
(`benchmarks/`), con Spring, Quarkus, Micronaut y Javalin ya configurados en Docker — es la línea
base contra la que se mide la arquitectura nueva.

Lo que cambia es el modelo de ejecución. JxMVC es un WAR que necesita Tomcat 10.1+. LuxCore
arranca solo, con servidor HTTP propio, cero dependencias externas, y con la mira puesta en dejar
de ser únicamente un framework de Java.

La línea base medida que hay que superar (`benchmarks/results/RESULTS-docker.md`, 4 cpus / 2 GB):

| Framework | Arranque (ms) | RSS (MB) | rps /json |
|---|---|---|---|
| jxmvc (sobre Tomcat) | 1392 | 471.8 | 43 315 |
| spring | 2582 | 432.8 | 44 884 |
| quarkus | 878 | 411.5 | 46 708 |
| micronaut | 1157 | 331.7 | 45 196 |
| javalin | 466 | 471.2 | 47 667 |

Los 1392 ms de arranque de JxMVC son, en su mayor parte, Tomcat levantándose.
