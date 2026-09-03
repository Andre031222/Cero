# El sitio de demostración

> **Esto no es el sitio público.** `cero.ginit.dev` lo sirve un repositorio aparte —
> `~/Research/Software/51.Soft_Cero-Web`, React más un backend Java en un solo jar. Lo que se
> documenta aquí es `docs/web` y las plantillas de `cero-web`: la **aplicación de demostración**
> que vive dentro del framework y que sus pruebas usan para comprobar que todo encaja de punta a
> punta. Las trampas de CSS y de captura del final siguen valiendo para los dos.

Cómo se construye y se traduce la demo, y las trampas que ya han costado un rato encontrar.

## La demo la sirve Java, no un servidor de estáticos

Esto es lo primero que hay que entender, porque no es evidente:

- **La demo la sirve `cero-web`**, la aplicación Java, con una ruta por página en
  `InicioController`. Comprobación rápida: `/guia` responde 200 y `/guia.html` responde 404.
- **`docs/web` no está publicado en ninguna parte.** No hay flujo de GitHub Pages. Es la
  *fuente*: de ahí salen las plantillas que sirve `cero-web`.

O sea que un cambio que solo toque `docs/web` y no llegue a `java/cero-web` **no se ve en
producción**.

## La cadena de construcción

```bash
python3 docs/web/construir.py     # contenido/ + assets/ → HTML completo en docs/web/
python3 docs/web/a-plantillas.py  # ese HTML → plantillas de cero-web + copia de recursos
```

Siempre en ese orden, y siempre las dos. `construir.py` genera además `completo.html`, el sitio
entero en un archivo para leerlo sin conexión (solo en castellano: duplicarlo doblaría 158 KB).

| Archivo | Qué es |
|---|---|
| `docs/web/contenido/*.html` | El cuerpo de cada página, en castellano. **Aquí se escribe.** |
| `docs/web/contenido/en/*.html` | Lo mismo en inglés. |
| `docs/web/assets/cero.css` · `cero.js` · `terminal.js` | Estilos y guiones. **Aquí se escribe.** |
| `docs/web/marca/` | Favicon, imagen social y su plantilla. |
| `docs/web/*.html` y `docs/web/en/*.html` | **Generados.** No editar. |
| `java/cero-web/…/plantillas/*.html` | **Generados**, menos `base.html`. |
| `java/cero-web/…/estaticos/` | **Copiados** desde `assets/` y `marca/`. |

### Trampa: `base.html` se mantiene a mano

`java/cero-web/src/main/resources/plantillas/base.html` es la única plantilla que **no** se
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

La marca es un **cuervo grabado en el siglo XVIII**, en **dominio público**.

| | |
|---|---|
| Autor | **François-Nicolas Martinet** (1731–1800) |
| Obra | *Histoire Naturelle des Oiseaux* de Buffon, lámina 495 — «Le Corbeau» |
| Ejemplar | Iconographia Zoologica, Colecciones Especiales de la Universidad de Ámsterdam, UBA01 IZ15700199 |
| Licencia | Dominio público. El autor murió en 1800, así que lo es en toda jurisdicción |
| Vía | [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Corvus_corax_-_1700-1880_-_Print_-_Iconographia_Zoologica_-_Special_Collections_University_of_Amsterdam_-_UBA01_IZ15700199.tif) |

De la lámina solo se usa el ave. Recortarla tuvo su gracia: **está posada sobre una roca musgosa
tan oscura como ella**, así que por umbral de luminancia no había forma de separarlas. Lo que
funciona es la **saturación**: la roca es olivácea y el ave neutra, de modo que el alfa se
construye multiplicando «oscuridad» por «poca saturación». Después, quedarse con el componente
conexo mayor limpia las motas del papel y del marco.

| Recurso | Dónde se usa | Cómo se hizo |
|---|---|---|
| `assets/cuervo.webp` | Portada y cabecera de Empezar | Ave aislada por saturación, 700 px, 12 KB |
| `assets/cuervo-marca.png` | Barra superior | La misma ave sin patas, engrosada para tamaños pequeños |
| `marca/favicon.svg` | Pestaña del navegador | La silueta en magenta dentro de una caja de 64 |

Todos van como **máscara CSS** sobre `var(--tinta)` o `var(--acento)`: un solo archivo sirve para
claro y para oscuro, porque el color lo pone el tema.

> **Antes hubo dos cuervos que no valieron, y conviene saber por qué.**
>
> El primero era un dibujo a lápiz precioso, pero resultó ser de **SEO/BirdLife**: el archivo
> conservaba el atributo `kMDItemWhereFroms` de macOS apuntando a `atlasaves.seo.org`, y la huella
> SHA-256 coincidía byte a byte con la del original. **Lección: antes de usar una imagen, mirar
> `xattr -l` y el EXIF** — el archivo casi siempre dice de dónde viene.
>
> El segundo lo dibujé a mano en vector para salir del paso. Era original y legalmente limpio,
> pero no daba la talla. De ahí se sale mejor buscando en el **dominio público**: las láminas
> ornitológicas anteriores a 1900 son dibujos reales, excelentes y sin derechos, y Wikimedia
> Commons expone la licencia de cada archivo por API.

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

Están comentadas en `cero.css`, pero conviene tenerlas juntas:

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
- Revelado al hacer scroll. **La clase `.revelar` la pone `cero.js`, nunca el HTML**: si el guion no
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
La demo **no se despliega en ninguna parte**: existe para que las pruebas la ejerciten. El que se
despliega es el sitio público, desde el repositorio 51, con
`~/Research/Contabo/GinitDev/apps/cero/desplegar.sh`.
