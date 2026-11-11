# Seguridad

## Cómo avisar de una vulnerabilidad

No abras un issue público. Usa el aviso privado de GitHub: en el repositorio, pestaña
**Security**, botón **Report a vulnerability**
([enlace directo](https://github.com/Andre031222/Cero/security/advisories/new)). El informe solo lo
ven los mantenedores hasta que se publique la corrección.

## Qué versiones se corrigen

| Versión | Recibe correcciones |
|---|---|
| La última publicada en Maven Central (hoy 0.8.x) | Sí |
| `main` | Sí |
| Las anteriores | No |

Una versión publicada no se toca: la corrección sale en una versión nueva. Lo cuenta
[docs/versiones.md](docs/versiones.md).

## Qué incluir en el informe

- La implementación afectada (Java, Rust, C++ o Python) y la versión o el commit.
- Qué puede hacer quien ataca y qué hace falta para conseguirlo.
- Los pasos para reproducirlo, mejor con una petición HTTP cruda o un programa mínimo.
- Lo que esperabas que pasara y lo que pasó.
- Si lo sabes, el requisito de `spec/` que se incumple.

## Cuánto tarda la respuesta

Cero es un proyecto académico que mantiene una sola persona, sin guardias ni horario. Se hace lo
posible por acusar recibo en una semana y por tener una corrección o un plan en treinta días, pero
no hay plazo garantizado. Al publicar la corrección, el aviso reconoce a quien informó, si así lo
quiere.

## In English

Do not open a public issue. Report vulnerabilities privately through GitHub Security Advisories
(**Security** tab, **Report a vulnerability**). Fixes go to the latest release on Maven Central
(currently 0.8.x) and to `main`; older releases are not patched. Include the affected
implementation and version, the impact, reproduction steps and, if known, the `spec/` requirement
that breaks. This is an academic project with a single maintainer, so response times are
best-effort: an acknowledgement within a week and a fix or plan within thirty days are the goal,
not a guarantee.
