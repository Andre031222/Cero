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

PAGINAS = [
    # archivo,           título,                        entradilla,                                                        en el menú
    ("index.html",       "LuxCore",                     "Framework web para Java que arranca solo, sin contenedor y sin una sola dependencia externa.", None),
    ("empezar.html",     "Empezar",                     "De cero a un servidor respondiendo, en cuatro órdenes.",           "Empezar"),
    ("guia.html",        "Guía",                        "Rutas, parámetros, respuestas, inyección, validación y errores.",  "Guía"),
    ("modulos.html",     "Módulos",                     "Qué trae cada uno y cómo se usan por separado.",                   "Módulos"),
    ("referencia.html",  "Referencia",                  "Tamaños, motores de base de datos, sistemas y comparación.",       "Referencia"),
    ("estado.html",      "Estado",                      "Qué está probado, qué no, y qué falta para producción.",           "Estado"),
    ("marca/index.html", "Logo",                        "Construcción, tamaños y uso de la marca.",                         "Logo"),
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
        f'\n<script src="{raiz}/assets/terminal.js" defer></script>' if lleva_terminal else ""
    )

    return f"""<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{completo}</title>
<meta name="description" content="{entradilla}">
<link rel="icon" href="data:image/svg+xml;base64,{icono}">
<link rel="stylesheet" href="{raiz}/assets/lux.css">
<script src="{raiz}/assets/lux.js"></script>{guion_terminal}
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
      Richar Andre Vilca-Solorzano<br>
      Universidad Nacional del Altiplano · Puno, Perú
    </div>
    <div class="etiqueta" style="text-align:right">
      LuxCore 0.1.0 · Licencia MIT<br>
      Medido el 2 de agosto de 2026
    </div>
  </footer>
</div>

</body>
</html>
"""


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
    raise SystemExit(construir())
