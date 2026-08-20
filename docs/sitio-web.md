# El sitio web

Cómo se construye, se traduce y se despliega **luxcore.ginit.dev**, y las trampas que ya han
costado un rato encontrar.

## Hay un solo sitio, y lo sirve Java

Esto es lo primero que hay que entender, porque no es evidente:

- **`luxcore.ginit.dev` lo sirve `lux-web`**, la aplicación Java, con una ruta por página en
  `InicioController`. Comprobación rápida: `/guia` responde 200 y `/guia.html` responde 404.
- **`docs/web` no está publicado en ninguna parte.** No hay flujo de GitHub Pages. Es la
  *fuente*: de ahí salen las plantillas que sirve `lux-web`.

O sea que un cambio que solo toque `docs/web` y no llegue a `java/lux-web` **no se ve en
producción**.

## La cadena de construcción

```bash
python3 docs/web/construir.py     # contenido/ + assets/ → HTML completo en docs/web/
python3 docs/web/a-plantillas.py  # ese HTML → plantillas de lux-web + copia de recursos
```

Siempre en ese orden, y siempre las dos. `construir.py` genera además `completo.html`, el sitio
entero en un archivo para leerlo sin conexión (solo en castellano: duplicarlo doblaría 158 KB).

| Archivo | Qué es |
|---|---|
| `docs/web/contenido/*.html` | El cuerpo de cada página, en castellano. **Aquí se escribe.** |
| `docs/web/contenido/en/*.html` | Lo mismo en inglés. |
| `docs/web/assets/lux.css` · `lux.js` · `terminal.js` | Estilos y guiones. **Aquí se escribe.** |
| `docs/web/marca/` | Favicon, imagen social y su plantilla. |
| `docs/web/*.html` y `docs/web/en/*.html` | **Generados.** No editar. |
| `java/lux-web/…/plantillas/*.html` | **Generados**, menos `base.html`. |
| `java/lux-web/…/estaticos/` | **Copiados** desde `assets/` y `marca/`. |

### Trampa: `base.html` se mantiene a mano

`java/lux-web/src/main/resources/plantillas/base.html` es la única plantilla que **no** se
genera: lleva la barra, el pie y el `<head>` del sitio dinámico. `a-plantillas.py` no la toca.

Ya mordió una vez: se cambió el logo en `construir.py`, se regeneró todo, y la barra de
producción siguió con el logo viejo porque vivía incrustado en `base.html`. **Cualquier cambio en
la cabecera hay que hacerlo en los dos sitios.**

## Bilingüe

El castellano vive en la raíz y el inglés bajo `/en`. **Los nombres de archivo no se traducen**
(`/en/guia`, no `/en/guide`): así cada página y su pareja se corresponden sin tabla de
equivalencias que se desincronice, y el `hreflang` sale de una sustitución.

Para añadir o cambiar una página hay que tocar cuatro sitios:

1. `contenido/<pagina>.html` y `contenido/en/<pagina>.html` — el cuerpo.
2. `construir.py` — `PAGINAS` (castellano) y `EN` (títulos, entradillas y etiqueta de menú).
   `TEXTOS` tiene lo que rodea al contenido: pie, barra inferior, etiquetas de accesibilidad.
3. `a-plantillas.py` — `PAGINAS`, que mapea fragmento → plantilla.
4. `InicioController.java` — una ruta `@Get("/…")` y otra `@Get("/en/…")`.

El conmutador de idioma, el `lang` del documento y las etiquetas `hreflang` salen de
`Vista.modelo()`, que deduce de la ruta actual cuál es su pareja en el otro idioma.

Si falta una traducción, `construir.py` **avisa y no genera esa página**: una página a medias
indexada es peor que una que no existe.

## La marca

La marca es un **cuervo dibujado de cero en vector**, en `marca/cuervo.svg`: silueta maciza con
el plumaje calado, 2 KB, y toma el color del tema con `currentColor`, así que sirve igual en
claro y en oscuro sin duplicar archivos.

Va **incrustado**, no como imagen: en la portada y en la cabecera de Empezar por el propio
fragmento de contenido, y en la barra a través de `logo_svg()` de `construir.py` y de `base.html`
en `lux-web`. La versión de la barra es la misma quitándole patas y percha, que a ese tamaño
solo ensucian.

Los calados van pintados en `var(--papel)` y **no por máscara**, a propósito: una máscara obliga
a un identificador único, y el dibujo aparece dos veces en la misma página. Como la marca
siempre se apoya sobre el fondo de la página, el resultado es el mismo y no hay ids que choquen.

El favicon es el mismo dibujo en magenta, recortado a su caja.

> **Hubo antes otro cuervo, y hubo que retirarlo.** Era un dibujo a lápiz precioso, pero resultó
> ser de **SEO/BirdLife**: el archivo conservaba el atributo `kMDItemWhereFroms` de macOS
> apuntando a `atlasaves.seo.org`, y la huella SHA-256 coincidía byte a byte con la del original.
> Se sustituyó por el dibujo propio y se borraron el JPG y sus cinco derivados. **Lección: antes
> de usar una imagen, mirar `xattr -l` y el EXIF** — el archivo casi siempre dice de dónde viene.

### La imagen que sale al compartir el enlace

`marca/social.html` es la fuente de `marca/social.png` (1200×630). Se rinde con Chrome para usar
la tipografía real del sitio:

```bash
python3 -m http.server 8192 --directory docs/web &
chrome --headless --window-size=1200,630 \
       --screenshot=social.png http://127.0.0.1:8192/marca/social.html
```

**Tiene que servirse por HTTP, no por `file://`**: las máscaras CSS están sujetas a CORS y sobre
`file://` Chrome las bloquea — la imagen sale sin el cuervo y sin avisar.

El cuervo va en la banda central a propósito: los mensajeros recortan la miniatura hacia el
centro, y pegado al borde derecho se quedaba fuera.

## Temas

Dos, y cada color se define **tres veces**: en `:root`, en `@media (prefers-color-scheme: dark)`
y en `:root[data-theme="dark"]`, para que el conmutador gane en las dos direcciones. En oscuro el
papel es negro puro (`#000000`).

## Trampas de CSS que ya costaron caro

Están comentadas en `lux.css`, pero conviene tenerlas juntas:

- **Ítems de grid que no encogen.** `.cuerpo` es un grid y sus ítems tienen `min-width: auto`, así
  que no bajan del ancho de su contenido. Con `table { min-width: 32rem }` la columna se plantaba
  en 512 px y desbordaba la página entera en el teléfono, dejando inútiles los `.envoltura` con
  `overflow-x`. Se arregla con `minmax(0, 1fr)` y `min-width: 0`.
- **`<pre>` fuera de `.codigo`.** Los de `.aviso` derramaban 771 px en cajas de 314. La regla de
  desplazamiento va en `pre` a secas, para que cubra los que se escriban mañana.
- **Especificidad.** `section:first-child { padding-top: 0 }` es (0,1,1) y le ganaba a `.portada`
  (0,1,0): su `padding-top` no se aplicaba nunca. Por eso el selector es `section.portada`.
- **Desbordar rompe la barra inferior.** `.nav-movil` es `position: fixed`, anclada al viewport de
  composición; si la página desborda a lo ancho, ese viewport se ensancha y la barra se va con él
  al desplazarse. Los dos síntomas son el mismo fallo.
- **Posicionar contra el ancestro equivocado.** La escena del cuervo de Empezar va *dentro* de
  `.cabecera-pagina`. Fuera, se posicionaba contra un ancestro más arriba y la barra fija le
  cortaba la cabeza.

## Animaciones

Todas bajo `@media (prefers-reduced-motion: no-preference)`:

- Entrada escalonada en la portada y aterrizaje del cuervo en Empezar, por CSS.
- Revelado al hacer scroll. **La clase `.revelar` la pone `lux.js`, nunca el HTML**: si el guion no
  corre, el contenido se ve igual. Y lo que ya está en pantalla al cargar no se anima, que si no
  parpadea bajo el pulgar.

## Cómo mirarlo antes de desplegar

```bash
python3 -m http.server 8192 --directory docs/web    # previsualización estática, rápida
```

Dos trampas al capturar con Chrome sin ventana:

- **El tema.** Hereda el del sistema. Para forzar el claro,
  `--blink-settings=preferredColorScheme=1`. `--force-dark-mode` **no** sirve: activa la inversión
  automática de Chrome, que no es el `prefers-color-scheme` del sitio.
- **El móvil.** `--window-size=390,N` a secas no aplica el `<meta viewport>` y da una captura que
  *parece* recortada sin estarlo. Hay que emular de verdad con CDP
  (`Emulation.setDeviceMetricsOverride` con `mobile: true`), **reaplicándolo después de navegar**.

Para medir desbordes: `documentElement.scrollWidth` contra un ancho fijo. No filtrar por «ancestro
con scroll» — `nav.indice` tiene `overflow-y: auto`, y eso hace que `overflow-x` compute a `auto`
y esconde a los culpables. Lo que funciona es buscar `scrollWidth > clientWidth` en elementos con
`overflow-x: visible`.

## Desplegar

Ver [produccion.md](produccion.md) y el guion
`~/Research/Contabo/GinitDev/apps/luxcore/desplegar.sh`. Empaqueta con `./lux paquete`, que corre
la batería completa de todos los módulos (~400 s) antes de subir nada.
