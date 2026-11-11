# Autoría

Cero es obra de Richar Andre Vilca-Solorzano, de la **Universidad Nacional del Altiplano**,
Facultad de Ingeniería Estadística e Informática, Puno, Perú.

| Autor | ORCID | Aportación |
|---|---|---|
| Richar Andre Vilca-Solorzano | [0009-0003-2385-5263](https://orcid.org/0009-0003-2385-5263) | Diseño, implementación, medición y redacción |

Cero se distribuye bajo licencia Apache 2.0. El aviso de copyright está en [LICENSE](../LICENSE) y
los avisos de terceros, en [NOTICE](../NOTICE).

## Procedencia

Cero empezó como una bifurcación de JxMVC 3.4.0 y se reescribió entero; hoy comparte menos del
1 % de sus líneas, todas genéricas.

Cómo se mide: de cada proyecto se toman las líneas de sus `.java`, sin la sangría ni los espacios
del final; se quedan las de 40 caracteres o más, para no contar llaves ni `return null;`; se
ordenan y se quitan las repetidas. `comm -12` da las comunes:

```
lineas() { find "$1" -name '*.java' -not -path '*/target/*' -print0 | xargs -0 cat \
  | LC_ALL=C sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | awk 'length >= 40' | LC_ALL=C sort -u; }
lineas ruta/a/jxmvc > jxmvc.txt
lineas java > cero.txt
LC_ALL=C comm -12 jxmvc.txt cero.txt | wc -l
```

La última medición, del 11 de noviembre de 2026, dio 46 líneas comunes de 7 513 distintas de Cero
(0,6 %). Son `import` del JDK y de `jakarta.servlet`, bucles como
`for (int i = 0; i < params.length; i++) {`, cabeceras HTTP como
`.header("Content-Type", "application/json")`, la URL de autorización de Google y algunas líneas
de `pom.xml` que van dentro de las plantillas de `cero new`: líneas que escribiría igual cualquiera
que use el JDK y Maven.

## Sobre las cabeceras del código

Los archivos fuente no llevan cabecera de autoría. La autoría vive en `LICENSE` y en este
documento, una sola vez, y no repetida en cada archivo. Es coherente con el estilo del proyecto:
el código se explica solo, sin comentarios decorativos.

## Citación

```bibtex
@software{vilcasolorzano2026cero,
  title  = {Cero: núcleo de framework web poliglota sin dependencias},
  author = {Vilca-Solorzano, Richar Andre},
  year   = {2026},
  url    = {https://github.com/Andre031222/Cero}
}
```
