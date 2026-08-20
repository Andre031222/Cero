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

# lux-web sirve su propia copia de los recursos, así que hay que copiarlos.
#
# Esto NO es una lista a mano a propósito. Hubo tres: terminal.js anunciando 1 241 pruebas tres
# cambios después, social.png con la paleta vieja, y el favicon dorado mucho después de que la
# marca fuera magenta. Cada vez, el archivo estaba fuera de la lista y nadie se enteraba. Se
# copian TODOS los de assets/ y los de marca/ que el sitio enlaza; si mañana aparece uno nuevo,
# entra solo.
CARPETAS = ("assets", "marca")

# Y lo que vive en subcarpetas: las fuentes alojadas.
SUBCARPETAS = ("assets/fuentes",)

# Lo que arma ./lux dist y no viene de aquí: no se toca al sincronizar.
GENERADOS = ("luxcore-",)

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


def preparar(cuerpo: str, idioma: str = "es") -> str:
    """Escapa la sintaxis de plantilla de los ejemplos y reescribe los enlaces a rutas de lux-web."""
    cuerpo = cuerpo.replace("{%", "&#123;&#37;").replace("%}", "&#37;&#125;")
    cuerpo = cuerpo.replace("{{", "&#123;&#123;").replace("}}", "&#125;&#125;")
    prefijo = "" if idioma == "es" else "/en"
    for viejo, nuevo in RUTAS.items():
        destino = (prefijo + nuevo) if idioma != "es" else nuevo
        if idioma != "es" and nuevo == "/":
            destino = "/en"
        cuerpo = cuerpo.replace(f'href="{viejo}"', f'href="{destino}"')
    return cuerpo


def sincronizar() -> int:
    """Copia a lux-web todo recurso del sitio, y avisa de lo que sobra por allí."""
    ESTATICOS.mkdir(parents=True, exist_ok=True)
    origenes = {}
    for carpeta in CARPETAS:
        for archivo in sorted((AQUI / carpeta).glob("*")):
            if archivo.is_file():
                origenes[archivo.name] = archivo

    for nombre, archivo in origenes.items():
        (ESTATICOS / nombre).write_bytes(archivo.read_bytes())

    for sub in SUBCARPETAS:
        destino = ESTATICOS / pathlib.Path(sub).name
        destino.mkdir(parents=True, exist_ok=True)
        for archivo in sorted((AQUI / sub).glob("*")):
            if archivo.is_file():
                (destino / archivo.name).write_bytes(archivo.read_bytes())
                origenes[f"{pathlib.Path(sub).name}/{archivo.name}"] = archivo

    for servido in sorted(ESTATICOS.glob("*")):
        if not servido.is_file() or servido.name in origenes:
            continue
        if any(servido.name.startswith(marca) for marca in GENERADOS):
            continue
        if servido.is_dir():
            continue
        print(f"  ¡ojo! {servido.name} lo sirve lux-web y no tiene original en docs/web")

    return len(origenes)


def main() -> int:
    if not PLANTILLAS.is_dir():
        print(f"no encuentro {PLANTILLAS}", file=sys.stderr)
        return 1

    hechas = 0
    for idioma in ("es", "en"):
        origen = FRAGMENTOS if idioma == "es" else FRAGMENTOS / "en"
        salida = PLANTILLAS if idioma == "es" else PLANTILLAS / "en"
        salida.mkdir(parents=True, exist_ok=True)

        for fragmento, plantilla in PAGINAS.items():
            fuente = origen / fragmento
            if not fuente.is_file():
                print(f"  sin traducir: {idioma}/{fragmento}", file=sys.stderr)
                continue
            cuerpo = preparar(fuente.read_text(encoding="utf-8"), idioma)

            if idioma == "es" and plantilla in INDICE_EXTRA:
                viejo, nuevo = INDICE_EXTRA[plantilla]
                cuerpo = cuerpo.replace(viejo, nuevo, 1)

            if idioma == "es" and plantilla in EXTRAS:
                # El extra va dentro del <main>, justo antes de cerrarlo.
                corte = cuerpo.rindex("    </main>")
                cuerpo = cuerpo[:corte] + EXTRAS[plantilla] + cuerpo[corte:]

            destino = salida / f"{plantilla}.html"
            destino.write_text(
                '{% extends "base" %}\n\n{% block contenido %}\n' + cuerpo + "\n{% end %}\n",
                encoding="utf-8")
            etiqueta = destino.name if idioma == "es" else f"en/{destino.name}"
            print(f"  {etiqueta:16} {destino.stat().st_size:>7} B")
            hechas += 1

    copiados = sincronizar()
    print(f"\n{hechas} plantillas y {copiados} recursos copiados a lux-web")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
