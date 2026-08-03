#!/usr/bin/env python3
"""Genera el sitio de LuxCore a partir de los fragmentos de contenido/.

Cada página es un fragmento con su cuerpo; este guion le pone la cabecera, la
navegación, el pie y los enlaces a los recursos compartidos. Así la navegación
se escribe una sola vez.

    python3 docs/web/construir.py
"""

import base64
import pathlib
import re
import sys

AQUI = pathlib.Path(__file__).resolve().parent

# Huella del contenido de los recursos: cambia cuando cambian, y así el navegador
# no sirve la hoja de estilo vieja después de tocarla.
def _huella():
    import hashlib
    datos = b"".join((AQUI / "assets" / n).read_bytes()
                      for n in ("lux.css", "lux.js", "terminal.js"))
    return hashlib.sha256(datos).hexdigest()[:8]

PAGINAS = [
    # archivo,           título,                        entradilla,                                                        en el menú
    ("index.html",       "LuxCore",                     "Framework web para Java que arranca solo, sin contenedor y sin una sola dependencia externa.", None),
    ("descargas.html",   "Descargas",                   "Los cinco módulos con su tamaño, huella y coordenadas de Maven.",  "Descargas"),
    ("empezar.html",     "Empezar",                     "De cero a un servidor respondiendo, en cuatro órdenes.",           "Empezar"),
    ("guia.html",        "Guía",                        "Rutas, parámetros, respuestas, inyección, validación y errores.",  "Guía"),
    ("modulos.html",     "Módulos",                     "Qué trae cada uno y cómo se usan por separado.",                   "Módulos"),
    ("referencia.html",  "Referencia",                  "Tamaños, motores de base de datos, sistemas y comparación.",       "Referencia"),
]


def logo_svg() -> str:
    bruto = (AQUI / "marca" / "logo.svg").read_text(encoding="utf-8")
    cuerpo = re.search(r"<svg[^>]*>(.*)</svg>", bruto, re.S).group(1)
    cuerpo = re.sub(r"\s*<title>.*?</title>\s*", "", cuerpo, flags=re.S).strip()
    return '<svg class="logo" viewBox="0 0 64 64" aria-hidden="true">' + cuerpo + "</svg>"


def favicon_incrustado() -> str:
    icono = (AQUI / "marca" / "favicon.svg").read_text(encoding="utf-8")
    return base64.b64encode(icono.encode()).decode()


def navegacion(actual: str, raiz: str) -> str:
    partes = []
    for archivo, _titulo, _entradilla, menu in PAGINAS:
        if not menu:
            continue
        activa = ' aria-current="page"' if archivo == actual else ""
        partes.append(f'<a href="{raiz}/{archivo}"{activa}>{menu}</a>')
    return "\n        ".join(partes)


BOTON_TEMA = """<button type="button" class="tema" aria-label="Cambiar de tema">
        <svg class="sol" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
             stroke-linecap="round" aria-hidden="true">
          <circle cx="12" cy="12" r="4"/>
          <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>
        </svg>
        <svg class="luna" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
             stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/>
        </svg>
      </button>"""


def envolver(archivo: str, titulo: str, entradilla: str, cuerpo: str, icono: str) -> str:
    raiz = ".." if "/" in archivo else "."
    completo = "LuxCore" if archivo == "index.html" else f"LuxCore — {titulo}"
    lleva_terminal = 'id="pantalla"' in cuerpo

    guion_terminal = (
        f'\n<script src="{raiz}/assets/terminal.js?v={_huella()}" defer></script>' if lleva_terminal else ""
    )

    return f"""<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{completo}</title>
<meta name="description" content="{entradilla}">
<link rel="icon" href="data:image/svg+xml;base64,{icono}">
<meta property="og:type" content="website">
<meta property="og:site_name" content="LuxCore">
<meta property="og:title" content="{completo}">
<meta property="og:description" content="{entradilla}">
<meta property="og:url" content="https://luxcore.ginit.dev/">
<meta property="og:image" content="https://luxcore.ginit.dev/estaticos/social.png">
<meta property="og:image:width" content="1200">
<meta property="og:image:height" content="630">
<meta property="og:image:alt" content="LuxCore — framework web para Java. 106 ms de arranque, 0 dependencias, 308 KB.">
<meta property="og:locale" content="es_ES">
<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:title" content="{completo}">
<meta name="twitter:description" content="{entradilla}">
<meta name="twitter:image" content="https://luxcore.ginit.dev/estaticos/social.png">
<link rel="stylesheet" href="{raiz}/assets/lux.css?v={_huella()}">
<script src="{raiz}/assets/lux.js?v={_huella()}"></script>{guion_terminal}
</head>
<body>

<header class="barra-sitio">
  <div class="marco">
    <a class="marca-sitio" href="{raiz}/index.html">
      {logo_svg()}
      <span class="palabra">Lux<em>Core</em></span>
    </a>
    <nav class="nav-sitio" aria-label="Secciones del sitio">
        {navegacion(archivo, raiz)}
    </nav>
    {BOTON_TEMA}
  </div>
</header>

<div class="marco">
{cuerpo}

  <footer class="pie">
    <div class="etiqueta">
      Vilca-Solorzano · Torres Cruz · Laura Murillo<br>
      Universidad Nacional del Altiplano · Puno, Perú
    </div>
    <div class="etiqueta" style="text-align:right">
      LuxCore 0.2.0 · Licencia MIT<br>
      Medido el 2 de agosto de 2026
    </div>
  </footer>
</div>

<nav class="nav-movil" aria-label="Secciones">
  <ul>
    <li><a href="{raiz}/" aria-label="Inicio">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V20h14V9.5"/><path d="M10 20v-5h4v5"/></svg>
      <span>Inicio</span></a></li>
    <li><a href="{raiz}/descargas.html" aria-label="Descargas">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v11"/><path d="M8 11l4 4 4-4"/><path d="M4 18v2h16v-2"/></svg>
      <span>Bajar</span></a></li>
    <li><a href="{raiz}/guia.html" aria-label="Guía">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H19v15H6.5A2.5 2.5 0 0 0 4 20.5z"/><path d="M8 7.5h7M8 11h7"/></svg>
      <span>Guía</span></a></li>
    <li><a href="{raiz}/modulos.html" aria-label="Módulos">
      <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/></svg>
      <span>Módulos</span></a></li>
  </ul>
</nav>

</body>
</html>
"""


PAGINAS_UNICO = [
    ("index.html", "Portada"),
    ("descargas.html", "Descargas"),
    ("empezar.html", "Empezar"),
    ("guia.html", "Guía"),
    ("modulos.html", "Módulos"),
    ("referencia.html", "Referencia"),
]


def unico() -> int:
    """Agrupa el sitio entero en un archivo, para leerlo sin servidor ni conexión."""
    estilo = (AQUI / "assets" / "lux.css").read_text(encoding="utf-8")
    guion = (AQUI / "assets" / "lux.js").read_text(encoding="utf-8")
    terminal = (AQUI / "assets" / "terminal.js").read_text(encoding="utf-8")
    icono = favicon_incrustado()

    hojas = []
    pestanas = []
    for archivo, menu in PAGINAS_UNICO:
        clave = "p-" + archivo.replace("/index.html", "").replace(".html", "")
        fuente = AQUI / "contenido" / archivo.replace("/", "-")
        if not fuente.is_file():
            continue
        cuerpo = fuente.read_text(encoding="utf-8")
        # los enlaces entre páginas pasan a ser cambios de pestaña
        for otro, _ in PAGINAS_UNICO:
            destino = "p-" + otro.replace("/index.html", "").replace(".html", "")
            cuerpo = cuerpo.replace(f'href="./{otro}"', f'href="#{destino}" data-hoja="{destino}"')
        hojas.append(f'<div class="hoja" id="{clave}" hidden>\n{cuerpo}\n</div>')
        pestanas.append(f'<a href="#{clave}" data-hoja="{clave}">{menu}</a>')

    cambio = """
(function () {
  'use strict';
  var hojas = Array.prototype.slice.call(document.querySelectorAll('.hoja'));
  var pestanas = Array.prototype.slice.call(document.querySelectorAll('[data-hoja]'));
  if (!hojas.length) { return; }

  function mostrar(clave, mover) {
    var encontrada = false;
    hojas.forEach(function (h) {
      var suya = h.id === clave;
      h.hidden = !suya;
      if (suya) { encontrada = true; }
    });
    if (!encontrada) { hojas[0].hidden = false; clave = hojas[0].id; }
    document.querySelectorAll('.nav-sitio a').forEach(function (a) {
      if (a.dataset.hoja === clave) { a.setAttribute('aria-current', 'page'); }
      else { a.removeAttribute('aria-current'); }
    });
    if (mover) { window.scrollTo({ top: 0, behavior: 'auto' }); }
  }

  pestanas.forEach(function (a) {
    a.addEventListener('click', function (e) {
      e.preventDefault();
      var clave = a.dataset.hoja;
      history.replaceState(null, '', '#' + clave);
      mostrar(clave, true);
    });
  });

  mostrar((location.hash || '#p-index').slice(1), false);
})();
"""

    documento = f"""<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>LuxCore — framework web para Java sin dependencias</title>
<meta name="description" content="LuxCore: framework web para Java que arranca solo, sin contenedor y sin dependencias externas.">
<link rel="icon" href="data:image/svg+xml;base64,{icono}">
<style>
{estilo}
.hoja[hidden] {{ display: none; }}
</style>
</head>
<body>

<header class="barra-sitio">
  <div class="marco">
    <a class="marca-sitio" href="#p-index" data-hoja="p-index">
      {logo_svg()}
      <span class="palabra">Lux<em>Core</em></span>
    </a>
    <nav class="nav-sitio" aria-label="Secciones del sitio">
        {chr(10).join("        " + t for t in pestanas).strip()}
    </nav>
    {BOTON_TEMA}
  </div>
</header>

<div class="marco">
{chr(10).join(hojas)}

  <footer class="pie">
    <div class="etiqueta">
      Vilca-Solorzano · Torres Cruz · Laura Murillo<br>
      Universidad Nacional del Altiplano · Puno, Perú
    </div>
    <div class="etiqueta" style="text-align:right">
      LuxCore 0.2.0 · Licencia MIT<br>
      Medido el 2 de agosto de 2026
    </div>
  </footer>
</div>

<script>
{guion}
</script>
<script>
{terminal}
</script>
<script>
{cambio}
</script>

</body>
</html>
"""
    destino = AQUI / "completo.html"
    destino.write_text(documento, encoding="utf-8")
    print(f"  completo.html          {destino.stat().st_size:6d} B  ({len(hojas)} páginas en un archivo)")
    return 0


def construir() -> int:
    icono = favicon_incrustado()
    generadas = 0

    for archivo, titulo, entradilla, _menu in PAGINAS:
        fuente = AQUI / "contenido" / (archivo.replace("/", "-").replace(".html", ".html"))
        if not fuente.is_file():
            print(f"  falta el contenido de {archivo} ({fuente.name})", file=sys.stderr)
            continue

        destino = AQUI / archivo
        destino.parent.mkdir(parents=True, exist_ok=True)
        destino.write_text(
            envolver(archivo, titulo, entradilla, fuente.read_text(encoding="utf-8"), icono),
            encoding="utf-8",
        )
        print(f"  {archivo:22} {destino.stat().st_size:6d} B")
        generadas += 1

    print(f"\n{generadas} páginas generadas en {AQUI}")
    return 0 if generadas == len(PAGINAS) else 1


if __name__ == "__main__":
    if "--unico" in sys.argv:
        raise SystemExit(unico())
    codigo = construir()
    raise SystemExit(codigo or unico())
