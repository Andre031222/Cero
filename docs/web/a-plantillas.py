#!/usr/bin/env python3
"""Convierte los fragmentos del sitio en plantillas de lux-web.

El sitio se escribe una sola vez, en `contenido/`. De ahí salen dos cosas: las páginas estáticas
de `docs/web/` —que las hace `construir.py`— y las plantillas del sitio dinámico, que las hace
esto. Sin este paso habría que mantener el mismo texto en dos sitios, y ya pasó: al regenerar se
perdió el formulario del generador.

    python3 docs/web/a-plantillas.py
"""

import pathlib
import sys

AQUI = pathlib.Path(__file__).resolve().parent
FRAGMENTOS = AQUI / "contenido"
RECURSOS = AQUI.parent.parent / "java" / "lux-web" / "src" / "main" / "resources"
PLANTILLAS = RECURSOS / "plantillas"
ESTATICOS = RECURSOS / "estaticos"

# lux-web sirve su propia copia de los assets. Si no se copian aquí, se separan sin avisar:
# terminal.js se quedó anunciando 1 241 pruebas en el sitio en vivo tres cambios después.
ASSETS = ("lux.css", "lux.js", "terminal.js")

# fragmento -> plantilla
PAGINAS = {
    "index.html": "inicio",
    "descargas.html": "descargas",
    "empezar.html": "empezar",
    "guia.html": "guia",
    "modulos.html": "modulos",
    "referencia.html": "referencia",
}

# ./pagina.html -> ruta que sirve lux-web
RUTAS = {f"./{f}": ("/" if p == "inicio" else f"/{p}") for f, p in PAGINAS.items()}

# Lo que solo existe en el sitio dinámico, porque necesita un servidor detrás.
EXTRAS = {
    "descargas": '''
      <section id="generar">
        <div class="titulo-seccion"><span class="indice-num">07</span><h2>Generar un proyecto</h2></div>

        <p class="medida">
          Esta página la sirve LuxCore, así que el formulario funciona de verdad: rellena y te
          descargas un proyecto Maven listo para arrancar — clase principal, un controlador,
          plantillas y hoja de estilo. Sin contenedor y sin <code>web.xml</code>.
        </p>

        <form method="post" action="/generar/descargar" class="formulario">
          <input type="hidden" name="_csrf" value="{{ csrf }}">
          <div class="campo-form">
            <label for="groupId">groupId</label>
            <input id="groupId" name="groupId" value="com.ejemplo">
          </div>
          <div class="campo-form">
            <label for="artifactId">artifactId</label>
            <input id="artifactId" name="artifactId" value="mi-app">
          </div>
          <div class="campo-form">
            <label for="appName">Nombre visible</label>
            <input id="appName" name="appName" value="Mi aplicación">
          </div>
          <div class="campo-form">
            <label for="db">Base de datos</label>
            <select id="db" name="db">
              <option value="ninguno">Ninguna</option>
              <option value="h2">H2</option>
              <option value="postgresql">PostgreSQL</option>
              <option value="mysql">MySQL</option>
            </select>
          </div>
          <button class="boton" type="submit">Descargar ZIP</button>
        </form>
      </section>
''',
}

INDICE_EXTRA = {
    "descargas": ('<li><a href="#licencia"><span class="n">06</span> Licencia</a></li>',
                  '<li><a href="#licencia"><span class="n">06</span> Licencia</a></li>\n'
                  '        <li><a href="#generar"><span class="n">07</span> Generar un proyecto</a></li>'),
}


def preparar(cuerpo: str) -> str:
    """Escapa la sintaxis de plantilla que aparece dentro de los ejemplos de código."""
    cuerpo = cuerpo.replace("{%", "&#123;&#37;").replace("%}", "&#37;&#125;")
    cuerpo = cuerpo.replace("{{", "&#123;&#123;").replace("}}", "&#125;&#125;")
    for viejo, nuevo in RUTAS.items():
        cuerpo = cuerpo.replace(f'href="{viejo}"', f'href="{nuevo}"')
    return cuerpo


def main() -> int:
    if not PLANTILLAS.is_dir():
        print(f"no encuentro {PLANTILLAS}", file=sys.stderr)
        return 1

    for fragmento, plantilla in PAGINAS.items():
        cuerpo = preparar((FRAGMENTOS / fragmento).read_text(encoding="utf-8"))

        if plantilla in INDICE_EXTRA:
            viejo, nuevo = INDICE_EXTRA[plantilla]
            cuerpo = cuerpo.replace(viejo, nuevo, 1)

        if plantilla in EXTRAS:
            # El extra va dentro del <main>, justo antes de cerrarlo.
            corte = cuerpo.rindex("    </main>")
            cuerpo = cuerpo[:corte] + EXTRAS[plantilla] + cuerpo[corte:]

        destino = PLANTILLAS / f"{plantilla}.html"
        destino.write_text(
            '{% extends "base" %}\n\n{% block contenido %}\n' + cuerpo + "\n{% end %}\n",
            encoding="utf-8")
        print(f"  {destino.name:16} {destino.stat().st_size:>7} B")

    for nombre in ASSETS:
        (ESTATICOS / nombre).write_bytes((AQUI / "assets" / nombre).read_bytes())

    print(f"\n{len(PAGINAS)} plantillas y {len(ASSETS)} assets copiados a lux-web")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
