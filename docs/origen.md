# Origen de este repositorio

> **Dónde está JxMVC.** La copia de `jxmvc-core` que había en `legacy/` se retiró el 3 de
> septiembre de 2026: este repositorio es solo Cero. El original vive en su propio repositorio
> (`19.Soft_JXMVC`), el artefacto `jxmvc:jxmvc-core:3.4.0` es inmutable, y la copia sigue en el
> historial de git de aquí. La medición de abajo se hizo contra ese árbol y se puede rehacer.


> **Estado a 30 de agosto de 2026 — medido, no recordado.**
>
> Este documento describe cómo arrancó el repositorio el 1 de agosto. **Ya no describe el
> código.** Se comparó clase por clase el árbol actual contra el núcleo de **JxMVC 3.4.0**, normalizando
> paquetes, importaciones, comentarios, espacios y los nombres del framework, para que un
> renombrado no contara como reescritura:
>
> | | |
> |---|---|
> | Clases de Cero hoy | **153** |
> | Idénticas a alguna de JxMVC | **0** |
> | Con parecido superior al 55 % | **2**, y las dos son `@FunctionalInterface` de un método — cualquier par se parece |
>
> **No sobrevive ninguna clase heredada.** El servidor HTTP —4 410 líneas, 39 clases— no existía
> en JxMVC: allí el servidor era Tomcat.
>
> Este documento se conserva igualmente. Es el registro de procedencia de un repositorio que
> acompaña artículos: que hoy no quede código heredado es una conclusión que se sostiene porque
> está medida y porque el punto de partida está escrito, no porque se haya borrado la página.


Cero —que hasta la versión 0.4.0 se llamó LuxCore— parte del código de **JxMVC 3.4.0**, copiado el **1 de agosto de 2026**.

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
  <origen>/ Cero/
```

Verificada con `diff -r`: **94 archivos `.java`** en ambos lados, sin diferencias de contenido.
Lo único que no vino: los tres `.DS_Store`, excluidos a propósito, y un directorio de
configuración local de herramientas que los permisos de macOS sobre el recurso SMB no dejaron
leer. Ninguno de los dos es código del framework.

## Por qué se copió en vez de trabajar sobre el original

**`19.Soft_JXMVC` está congelado.** Alimenta un artículo cuya carpeta es
`AUP_Papers/13.-JxMVC_SPE/`. Fue rechazado en la selección editorial de *Software: Practice and
Experience* en agosto de 2026, sin llegar a revisión por pares, por un motivo ajeno al contenido
del manuscrito; se está redirigiendo a otra revista. Los benchmarks se midieron en Arch
bare-metal bajo condiciones controladas y **cualquier cambio en el framework invalida esos
números**, que es lo que mantiene el congelado. El origen tiene además tres pendientes abiertos
(benchmark `/db` en Arch, DOI de Zenodo, autoría/ORCID) que siguen su curso sin relación con este
repositorio.

Tampoco se copió el `.git` del origen: su `origin` apunta al repositorio del artículo, y bastaría
un `git push` distraído desde aquí para reescribirlo. Este repositorio arranca con historial propio.

**Regla de trabajo: `19.Soft_JXMVC` es de solo lectura.** Nada de lo que ocurra en Cero se
propaga hacia allá.

## Qué se hereda y qué cambia

Se hereda el núcleo: 54 clases en `jxmvc/core/`, de las cuales **40 no importan nada de
`jakarta.*`** y se mudan sin tocarse. Se hereda también el banco de pruebas de rendimiento
(`benchmarks/`), con Spring, Quarkus, Micronaut y Javalin ya configurados en Docker — es la línea
base contra la que se mide la arquitectura nueva.

Lo que cambia es el modelo de ejecución. JxMVC es un WAR que necesita Tomcat 10.1+. Cero
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
