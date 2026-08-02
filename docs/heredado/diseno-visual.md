# Design

Sistema visual del sitio oficial JxMVC (JxMVC2x). Capturado del código real
(`header.jspf`, `assets/css/tailwind.css` precompilado, vistas JSP).

## Theme

Doble tema completo. Claro: fondo `#FFFFFF` puro. Oscuro: fondo `#000000` puro
(clase `dark` en `<html>`, toggle persistido en localStorage). Nunca fondos crema,
azules ni grises de página.

## Color

| Rol | Valor | Uso |
|---|---|---|
| Acento primario | `#0071E3` | botones, links, tabs activos, serie "JxMVC" en gráficos |
| Acento secundario | `#5E5CE6` | gradientes con el primario, serie 2 |
| Naranja | `#FF9500` | rol semántico (warning, serie 3) |
| Rojo | `#FF2D55` | rol semántico (errores, serie 4) |
| Verde | `#30D158` | rol semántico (éxito, "tests en verde") |
| Tinta clara | `#1D1D1F` sobre blanco | texto principal claro |
| Tinta oscura | `#F5F5F7` sobre negro | texto principal oscuro |
| Texto secundario | `#6E6E73` claro / `#8E8E93` oscuro | descripciones |

Gradiente de marca (solo titular del hero y logo):
`linear-gradient(110deg,#FF6B00,#FF2D55 28%,#0071E3 66%,#5E5CE6)`.

**Dataviz:** la serie del producto (JxMVC) siempre lleva el azul `#0071E3`;
competidores en neutros (`#8E8E93`/`#48484A`). Orden categórico fijo:
azul → índigo → naranja → rojo → verde. El color sigue a la entidad, nunca al rango.
Texto de valores y leyendas en tokens de texto, nunca en el color de la serie.

## Typography

Stack del sistema (`-apple-system, "SF Pro Display", Segoe UI, Roboto…`), código en
`SF Mono / Menlo / Consolas`. Titulares grandes con tracking apretado (≥ -0.03em),
`clamp()` para el hero (~2.9–4.6rem). Sin eyebrows en mayúsculas sobre los títulos.

## Components

- **jx-win**: ventana de código estilo macOS (barra con 3 puntos, tabs monoespaciados,
  border-radius 16px, borde 1px translúcido).
- **jx-cta-primary**: pill con gradiente azul→índigo, sombra azulada, hover -2px.
- **jx-cta-ghost**: pill con borde 1px translúcido.
- **jx-stat**: chip de dato (borde 1px, radio 15px, número 16px bold + unidad 10px).
- **Cards**: radio 12–16px, borde 1px `rgba(0,0,0,.10)` claro / `rgba(255,255,255,.09)` oscuro,
  sombra ≤ 8px solo en claro.
- **Gráficos**: canvas/SVG a mano (cero libs). Barras finas con extremos redondeados 4px
  anclados a la base, líneas 2px, gap de 2px entre rellenos adyacentes, grid recesivo,
  etiquetas directas selectivas, tooltip por marca, leyenda si ≥2 series.

## Motion

Transiciones 130–250ms `ease-out` (curvas fuertes tipo `cubic-bezier(.16,1,.3,1)`),
`scale(0.97)` en `:active` de botones, animación de barras al entrar al viewport
(width con cubic-bezier 1.3s), contadores `jx-counter` con tabular-nums.
`prefers-reduced-motion`: sin desplazamientos, solo fades.

## Layout

Contenedor `max-w-5xl` (hero compacto), secciones con ritmo variable, grids
responsivos sin breakpoints duros donde se pueda. El contenido ancho (tablas,
código) scrollea en su propio contenedor.
