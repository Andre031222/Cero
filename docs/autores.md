# Autoría

Cero es obra de tres autores de la **Universidad Nacional del Altiplano**, Facultad de Ingeniería
Estadística e Informática, Puno, Perú.

| Autor | ORCID | Aportación |
|---|---|---|
| Richar Andre Vilca-Solorzano | [0009-0003-2385-5263](https://orcid.org/0009-0003-2385-5263) | Diseño, implementación, medición y redacción |
| Fred Torres-Cruz | [0000-0003-0834-6834](https://orcid.org/0000-0003-0834-6834) | Conceptualización del contrato poliglota · supervisión |
| Ramiro Pedro Laura-Murillo | [0000-0003-1837-4871](https://orcid.org/0000-0003-1837-4871) | Conceptualización · supervisión |

No se reparte el trabajo por partes: los tres figuran como autores del proyecto.

## De dónde sale la idea que da forma al proyecto

Cero podría haber sido un framework web más para Java. Lo que lo convierte en otra cosa es la
**fase 3**: un contrato definido de forma neutral respecto al lenguaje e implementado varias
veces, con un banco de conformidad que juzga a todas por igual.

Esa idea es de **Fred Torres-Cruz**, y no es un detalle de encuadre: es la que decide la
arquitectura entera. Por ella existe `spec/` en vez de tomar el código Java como referencia, por
ella el banco manda bytes sobre un socket en lugar de llamar a una API, y por ella la pregunta
del proyecto dejó de ser «¿cuánto rinde este framework?» para pasar a ser **«¿qué supuestos mete
un lenguaje en un diseño sin que su autor se dé cuenta?»**.

Esa pregunta ya tiene cuatro respuestas medidas, y todas salieron de escribir la segunda
implementación:

| Hallazgo | Dónde apareció |
|---|---|
| «Cero dependencias» obliga a otro modelo de concurrencia: Java tiene hilos virtuales en la plataforma y `std` de Rust no | Al elegir la arquitectura |
| Hay requisitos que se prueban y otros que se vuelven irrepresentables; cuál es cuál lo decide el lenguaje | `SES-011`, `SEG-022` |
| El contrato estaba escrito con el vocabulario de un lenguaje: «que lanza» no es neutral | `OBS-005` |
| Hay requisitos que un lenguaje no puede cumplir sin dependencias, porque su biblioteca estándar es más pequeña | TLS |

Ninguna de las cuatro se podía obtener con una sola implementación.

**Y una precisión sobre el origen del código.** El primer autor contribuyó antes a JxMVC, un
framework MVC sobre Jakarta EE de R. P. Laura-Murillo. Cero es un sistema distinto, con servidor
HTTP propio: no sobrevive ninguna clase heredada de aquel, y la medición que lo establece está en
el historial del proyecto.

Cero se distribuye bajo licencia Apache 2.0. El aviso de copyright está en [LICENSE](../LICENSE) y
los avisos de terceros, en [NOTICE](../NOTICE).

## Sobre las cabeceras del código

Los archivos fuente no llevan cabecera de autoría. La autoría vive en `LICENSE` y en este
documento, una sola vez, y no repetida en cada archivo. Es coherente con el estilo del proyecto:
el código se explica solo, sin comentarios decorativos.

## Citación

```bibtex
@software{vilcasolorzano2026cero,
  title  = {Cero: núcleo de framework web poliglota sin dependencias},
  author = {Vilca-Solorzano, Richar Andre and Torres-Cruz, Fred and
            Laura-Murillo, Ramiro Pedro},
  year   = {2026},
  url    = {https://github.com/Andre031222/Cero}
}
```
