# Autoría

## LuxCore

**Richar Andre Vilca-Solorzano** — autor principal y responsable del diseño.
Universidad Nacional del Altiplano, Puno, Perú.

La arquitectura nueva de LuxCore —servidor HTTP propio, núcleo desacoplado del contenedor de
servlets, motor de vistas, especificación poliglota y sus implementaciones en Java, C++ y Rust—
es obra suya. El código nuevo se escribe bajo su autoría.

## Código heredado de JxMVC 3.4.0

Este repositorio parte de JxMVC (ver [ORIGEN.md](ORIGEN.md)), publicado bajo licencia MIT. Varias
clases del núcleo llevan en su cabecera la atribución original:

```
/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo
///  improved : R. Andre Vilca Solorzano
```

**Esas cabeceras se conservan mientras el archivo siga siendo código heredado.** La licencia MIT
permite reusar y modificar libremente, pero exige mantener el aviso de copyright — y borrar la
atribución de código ajeno sería una debilidad evitable, más aún con un artículo en revisión que
cita ese mismo trabajo.

El criterio es simple y no admite zona gris:

- Archivo **copiado o adaptado** de JxMVC → conserva su cabecera original, se le añade la nota de
  modificación de LuxCore.
- Archivo **escrito desde cero** para LuxCore (`lux-http`, `lux-view`, el spec, las
  implementaciones en Rust y C++) → cabecera de LuxCore, autoría de Richar Andre Vilca-Solorzano,
  sin herencia.

Cuando un archivo heredado se reescriba por completo —el caso previsto de `MainLxServlet` al
convertirse en `LuxDispatcher`— la cabecera pasa a LuxCore con una línea que reconoce el trabajo
previo del que partió.

## Licencia

MIT. Texto completo en [LICENSE](LICENSE), avisos de terceros en [NOTICE](NOTICE).

## Citación

```bibtex
@software{vilcasolorzano2026luxcore,
  title  = {LuxCore: núcleo de framework web poliglota sin dependencias},
  author = {Vilca-Solorzano, Richar Andre},
  year   = {2026},
  url    = {https://github.com/Andre031222/45.Soft_LuxCore}
}
```
