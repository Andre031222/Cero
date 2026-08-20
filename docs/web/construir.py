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
    ("index.html",       "LuxCore",                     "Framework web para Java que arranca solo, sin contenedor y sin una sola dependencia externa.", "Inicio"),
    ("descargas.html",   "Descargas",                   "Los cinco módulos con su tamaño, huella y coordenadas de Maven.",  "Descargas"),
    ("empezar.html",     "Empezar",                     "De cero a un servidor respondiendo, en cuatro órdenes.",           "Empezar"),
    ("guia.html",        "Guía",                        "Rutas, parámetros, respuestas, inyección, validación y errores.",  "Guía"),
    ("modulos.html",     "Módulos",                     "Qué trae cada uno y cómo se usan por separado.",                   "Módulos"),
    ("referencia.html",  "Referencia",                  "Tamaños, motores de base de datos, sistemas y comparación.",       "Referencia"),
]

# ── bilingüe ──────────────────────────────────────────────────────────────────
# El castellano vive en la raíz y el inglés bajo /en/. Los nombres de archivo NO se
# traducen (/en/guia, no /en/guide): así el par de cada página es evidente y el hreflang
# sale de una sustitución, sin tabla de equivalencias que se desincronice.

IDIOMAS = ("es", "en")

# archivo -> (título, entradilla, etiqueta de menú) en inglés
EN = {
    "index.html":      ("LuxCore",
                        "Web framework for Java that boots on its own, with no servlet container "
                        "and not a single external dependency.",
                        "Home"),
    "descargas.html":  ("Downloads",
                        "The five modules with their size, footprint and Maven coordinates.",
                        "Downloads"),
    "empezar.html":    ("Get started",
                        "From nothing to a server answering requests, in four commands.",
                        "Get started"),
    "guia.html":       ("Guide",
                        "Routes, parameters, responses, injection, validation and errors.",
                        "Guide"),
    "modulos.html":    ("Modules",
                        "What each one brings and how to use them separately.",
                        "Modules"),
    "referencia.html": ("Reference",
                        "Sizes, database engines, systems and comparison.",
                        "Reference"),
}

# Textos de la propia plantilla: pie, barra inferior, etiquetas de accesibilidad.
TEXTOS = {
    "es": {
        "locale": "es_ES",
        "secciones": "Secciones del sitio",
        "en_esta": "Secciones",
        "tema": "Cambiar de tema",
        "otro_idioma": "English",
        "otro_titulo": "Read this page in English",
        "pie_sede": "Universidad Nacional del Altiplano · Puno, Perú",
        "pie_licencia": "LuxCore 0.3.0 · Licencia MIT",
        "pie_medido": "Medido el 2 de agosto de 2026",
        "movil": ("Inicio", "Bajar", "Guía", "Módulos"),
        "og_alt": "LuxCore — framework web para Java. 106 ms de arranque, 0 dependencias, 308 KB.",
    },
    "en": {
        "locale": "en_US",
        "secciones": "Site sections",
        "en_esta": "Sections",
        "tema": "Switch theme",
        "otro_idioma": "Español",
        "otro_titulo": "Leer esta página en español",
        "pie_sede": "Universidad Nacional del Altiplano · Puno, Peru",
        "pie_licencia": "LuxCore 0.3.0 · MIT licence",
        "pie_medido": "Measured on 2 August 2026",
        "movil": ("Home", "Get", "Guide", "Modules"),
        "og_alt": "LuxCore — web framework for Java. 106 ms boot, 0 dependencies, 308 KB.",
    },
}


def textos(idioma: str, archivo: str):
    """Título, entradilla y etiqueta de menú de una página en el idioma pedido."""
    for a, titulo, entradilla, menu in PAGINAS:
        if a == archivo:
            return (titulo, entradilla, menu) if idioma == "es" else EN[archivo]
    raise KeyError(archivo)


def ruta_publica(archivo: str, idioma: str) -> str:
    """La URL que sirve lux-web: /guia, no /guia.html; y /en/guia en inglés."""
    hoja = "" if archivo == "index.html" else "/" + archivo.replace(".html", "")
    return ("/en" + hoja) if idioma == "en" else (hoja or "/")


def alternativas(archivo: str) -> str:
    base = "https://luxcore.ginit.dev"
    filas = [
        f'<link rel="alternate" hreflang="{i}" href="{base}{ruta_publica(archivo, i)}">'
        for i in IDIOMAS
    ]
    filas.append(f'<link rel="alternate" hreflang="x-default" href="{base}{ruta_publica(archivo, "es")}">')
    return "\n".join(filas)


def conmutador(archivo: str, idioma: str) -> str:
    otro = "en" if idioma == "es" else "es"
    destino = f"./en/{archivo}" if otro == "en" else f"../{archivo}"
    t = TEXTOS[idioma]
    return (f'<a class="idioma" href="{destino}" hreflang="{otro}" lang="{otro}" '
            f'title="{t["otro_titulo"]}">{t["otro_idioma"]}</a>')


def logo_svg() -> str:
    """El cuervo de la barra: el mismo dibujo de marca/cuervo.svg, sin patas ni percha.

    Va incrustado y no como imagen para que tome el color del tema con currentColor y
    quede nítido a cualquier tamaño.
    """
    bruto = (AQUI / "marca" / "cuervo.svg").read_text(encoding="utf-8")
    cuerpo = " ".join(re.sub(r"^<svg[^>]*>|</svg>\s*$|<!--.*?-->", "", bruto, flags=re.S).split())
    cuerpo = re.sub(r'<g stroke="currentColor".*?</g>', "", cuerpo, flags=re.S)
    return f'<svg class="logo" viewBox="0 0 420 296" aria-hidden="true">{cuerpo}</svg>'



def favicon_incrustado() -> str:
    icono = (AQUI / "marca" / "favicon.svg").read_text(encoding="utf-8")
    return base64.b64encode(icono.encode()).decode()


def navegacion(actual: str, idioma: str) -> str:
    partes = []
    for archivo, _titulo, _entradilla, menu in PAGINAS:
        if not menu:
            continue
        etiqueta = menu if idioma == "es" else EN[archivo][2]
        activa = ' aria-current="page"' if archivo == actual else ""
        # las páginas de un idioma son hermanas entre sí, así que el enlace es relativo
        partes.append(f'<a href="./{archivo}"{activa}>{etiqueta}</a>')
    return "\n        ".join(partes)


BOTON_TEMA = """<button type="button" class="tema" aria-label="__TEMA__">
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


def envolver(archivo: str, titulo: str, entradilla: str, cuerpo: str, icono: str,
             idioma: str = "es") -> str:
    # los recursos viven en la raíz del sitio; desde /en/ hay que subir un nivel
    raiz = ".." if idioma != "es" else "."
    t = TEXTOS[idioma]
    completo = "LuxCore" if archivo == "index.html" else f"LuxCore — {titulo}"
    lleva_terminal = 'id="pantalla"' in cuerpo

    guion_terminal = (
        f'\n<script src="{raiz}/assets/terminal.js?v={_huella()}" defer></script>' if lleva_terminal else ""
    )

    return f"""<!doctype html>
<html lang="{idioma}">
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
<meta property="og:url" content="https://luxcore.ginit.dev{ruta_publica(archivo, idioma)}">
{alternativas(archivo)}
<meta property="og:image" content="https://luxcore.ginit.dev/estaticos/social.png">
<meta property="og:image:width" content="1200">
<meta property="og:image:height" content="630">
<meta property="og:image:alt" content="{t['og_alt']}">
<meta property="og:locale" content="{t['locale']}">
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
    <a class="marca-sitio" href="./index.html">
      {logo_svg()}
      <span class="palabra">Lux<em>Core</em></span>
    </a>
    <nav class="nav-sitio" aria-label="{t['secciones']}">
        {navegacion(archivo, idioma)}
    </nav>
    {conmutador(archivo, idioma)}
    {BOTON_TEMA.replace("__TEMA__", t["tema"])}
  </div>
</header>

<div class="marco">
{cuerpo}

  <footer class="pie">
    <div class="etiqueta">
      Richar Andre Vilca-Solorzano · Ramiro Pedro Laura-Murillo<br>
      {t['pie_sede']}
    </div>
    <div class="etiqueta" style="text-align:right">
      {t['pie_licencia']}<br>
      {t['pie_medido']}
    </div>
  </footer>
</div>

<nav class="nav-movil" aria-label="{t['en_esta']}">
  <ul>
    <li><a href="./index.html" aria-label="{t['movil'][0]}">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V20h14V9.5"/><path d="M10 20v-5h4v5"/></svg>
      <span>{t['movil'][0]}</span></a></li>
    <li><a href="./descargas.html" aria-label="{t['movil'][1]}">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v11"/><path d="M8 11l4 4 4-4"/><path d="M4 18v2h16v-2"/></svg>
      <span>{t['movil'][1]}</span></a></li>
    <li><a href="./guia.html" aria-label="{t['movil'][2]}">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H19v15H6.5A2.5 2.5 0 0 0 4 20.5z"/><path d="M8 7.5h7M8 11h7"/></svg>
      <span>{t['movil'][2]}</span></a></li>
    <li><a href="./modulos.html" aria-label="{t['movil'][3]}">
      <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/></svg>
      <span>{t['movil'][3]}</span></a></li>
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
    """Agrupa el sitio entero en un archivo, para leerlo sin servidor ni conexión.

    Solo en castellano: el archivo único es para leer el sitio sin conexión, y duplicarlo
    en dos idiomas doblaría un archivo que ya pesa 155 KB.
    """
    t = TEXTOS["es"]
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
    {BOTON_TEMA.replace("__TEMA__", t["tema"])}
  </div>
</header>

<div class="marco">
{chr(10).join(hojas)}

  <footer class="pie">
    <div class="etiqueta">
      Richar Andre Vilca-Solorzano · Ramiro Pedro Laura-Murillo<br>
      {t['pie_sede']}
    </div>
    <div class="etiqueta" style="text-align:right">
      {t['pie_licencia']}<br>
      {t['pie_medido']}
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
    faltan = []

    for idioma in IDIOMAS:
        # el castellano en la raíz, el inglés bajo /en/
        carpeta_fuente = AQUI / "contenido" if idioma == "es" else AQUI / "contenido" / "en"
        carpeta_salida = AQUI if idioma == "es" else AQUI / "en"

        for archivo, _t, _e, _menu in PAGINAS:
            fuente = carpeta_fuente / archivo
            if not fuente.is_file():
                # sin traducir todavía: se avisa y NO se genera, que una página a medias
                # indexada en Google es peor que una que no existe
                faltan.append(f"{idioma}/{archivo}")
                continue

            titulo, entradilla, _m = textos(idioma, archivo)
            destino = carpeta_salida / archivo
            destino.parent.mkdir(parents=True, exist_ok=True)
            destino.write_text(
                envolver(archivo, titulo, entradilla,
                         fuente.read_text(encoding="utf-8"), icono, idioma),
                encoding="utf-8",
            )
            etiqueta = archivo if idioma == "es" else f"en/{archivo}"
            print(f"  {etiqueta:22} {destino.stat().st_size:6d} B")
            generadas += 1

    esperadas = len(PAGINAS) * len(IDIOMAS)
    print(f"\n{generadas} de {esperadas} páginas generadas en {AQUI}")
    if faltan:
        print(f"  sin traducir: {', '.join(faltan)}", file=sys.stderr)
    return 0 if generadas == esperadas else 1


if __name__ == "__main__":
    if "--unico" in sys.argv:
        raise SystemExit(unico())
    codigo = construir()
    raise SystemExit(codigo or unico())
