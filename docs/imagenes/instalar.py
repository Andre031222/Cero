#!/usr/bin/env python3
"""Dibuja instalar.gif: la instalación de Cero escribiéndose sola.

Es la misma terminal que la del sitio, pero como imagen, porque GitHub no ejecuta
JavaScript en el README. Se regenera cuando cambien las cifras:

    python3 docs/imagenes/instalar.py
"""

import pathlib

from PIL import Image, ImageDraw, ImageFont

AQUI = pathlib.Path(__file__).resolve().parent
# Dos temas: GitHub sirve uno u otro según el del lector, y una terminal negra en una página
# blanca canta tanto como al revés.
TEMAS = {
    "oscuro": dict(
        destino="instalar.gif", isotipo="logo-light.png",
        fondo=(6, 6, 7),         # --term-fondo #060607
        barra=(19, 19, 21),      # --term-barra #131315
        borde=(31, 31, 32),      # --term-borde sobre el fondo
        tinta=(242, 245, 250),   # --term-orden #f2f5fa
        tenue=(204, 210, 221),   # --term-texto #ccd2dd
        gris=(135, 141, 153),    # --term-tenue #878d99
        marca=(56, 189, 248),    # --acento #38bdf8
        verde=(63, 196, 140),
    ),
    "claro": dict(
        destino="instalar-claro.gif", isotipo="logo-primary.png",
        fondo=(247, 249, 252),   # --term-fondo #f7f9fc
        barra=(234, 238, 246),   # --term-barra #eaeef6
        borde=(219, 222, 225),
        tinta=(15, 20, 28),      # --term-orden #0f141c
        tenue=(43, 54, 83),      # --term-texto #2b3653
        gris=(97, 108, 135),     # --term-tenue #616c87
        marca=(29, 78, 216),     # --acento #1d4ed8
        verde=(21, 128, 61),
    ),
}

ANCHO = 1000
MARGEN = 22
ALTO_BARRA = 42
INTERLINEA = 27
SANGRIA = 26

# Los tokens los pone el tema en curso; son los mismos que `.terminal` en portada.css, para
# que esta imagen y la terminal del sitio sean la misma pieza.
TEMA = TEMAS["oscuro"]
ISOTIPO = None

MONO = ImageFont.truetype("/System/Library/Fonts/Menlo.ttc", 17)
MONO_FINA = ImageFont.truetype("/System/Library/Fonts/Menlo.ttc", 13)

# (clase, texto). orden = tipo de línea: como se pinta y si se teclea.
GUION = [
    ("comentario", "# 1 — instalar"),
    ("orden",      "curl -fsSL https://cero.ginit.dev/instalar | sh"),
    ("ok",         "entorno    Java 25 · Maven 3.9 · Darwin arm64"),
    ("ok",         "descargado cero-0.7.0.tar.gz · 344 KB"),
    ("ok",         "huella     sha256 c16628fd2d28c207…"),
    ("ok",         "compilado  ocho módulos en ~/.m2 · 45 s"),
    ("ok",         "orden cero  ~/.local/bin/cero"),
    ("blanco",     ""),
    ("comentario", "# 2 — un proyecto nuevo"),
    ("orden",      "cero new mi-app"),
    ("salida",     "9 archivos · com.ejemplo:mi-app"),
    ("blanco",     ""),
    ("comentario", "# 3 — arrancar"),
    ("orden",      "cd mi-app && mvn -q package && java -jar target/mi-app.jar"),
    ("arranque",   "cero · http://0.0.0.0:8080 · 2 rutas · 10 ms"),
    ("blanco",     ""),
    ("comentario", "# 4 — comprobar"),
    ("orden",      "curl -s localhost:8080/salud"),
    ("salida",     "ok"),
]

ALTO = MARGEN * 2 + ALTO_BARRA + 20 + len(GUION) * INTERLINEA + 22


def logo(imagen: Image.Image, cx: float, cy: float, lado: int) -> None:
    """El isotipo de la marca, el mismo archivo que sirve el sitio."""
    marca = ISOTIPO.resize((lado, lado), Image.LANCZOS)
    imagen.paste(marca, (int(cx - lado / 2), int(cy - lado / 2)), marca)


def lienzo() -> Image.Image:
    imagen = Image.new("RGB", (ANCHO, ALTO), TEMA["fondo"])
    d = ImageDraw.Draw(imagen)

    d.rounded_rectangle([MARGEN, MARGEN, ANCHO - MARGEN, ALTO - MARGEN],
                        radius=6, fill=TEMA["fondo"], outline=TEMA["borde"], width=1)
    # La barra de título lleva su propio fondo, como en el sitio.
    d.rounded_rectangle([MARGEN + 1, MARGEN + 1, ANCHO - MARGEN - 1, MARGEN + ALTO_BARRA],
                        radius=6, fill=TEMA["barra"])
    d.rectangle([MARGEN + 1, MARGEN + ALTO_BARRA - 6, ANCHO - MARGEN - 1, MARGEN + ALTO_BARRA],
                fill=TEMA["barra"])
    d.line([(MARGEN + 1, MARGEN + ALTO_BARRA), (ANCHO - MARGEN - 1, MARGEN + ALTO_BARRA)], fill=TEMA["borde"])

    centro = MARGEN + ALTO_BARRA / 2
    for i, color in enumerate([(255, 95, 87), (254, 188, 46), (40, 200, 64)]):
        x = MARGEN + 20 + i * 19
        d.ellipse([x, centro - 5.5, x + 11, centro + 5.5], fill=color)

    logo(imagen, MARGEN + 96, centro, 26)
    d.text((MARGEN + 114, centro - 8), "Cero", font=MONO, fill=TEMA["tinta"])
    d.text((ANCHO / 2 + 40, centro - 6), "~/proyectos", font=MONO_FINA, fill=TEMA["gris"],
           anchor="mm")
    return imagen


def pintar(hasta: int, escritas: int) -> Image.Image:
    """Fotograma con las líneas 0..hasta-1 completas y `escritas` letras de la siguiente."""
    imagen = lienzo()
    d = ImageDraw.Draw(imagen)
    y = MARGEN + ALTO_BARRA + 18

    def linea(clase: str, texto: str, cursor: bool) -> None:
        nonlocal y
        x = MARGEN + SANGRIA
        if clase == "orden":
            d.text((x, y), "$", font=MONO, fill=TEMA["marca"])
            x += MONO.getlength("$ ")
            d.text((x, y), texto, font=MONO, fill=TEMA["tinta"])
            ancho = MONO.getlength(texto)
        elif clase == "ok":
            d.text((x + MONO.getlength("  "), y), "✓", font=MONO, fill=TEMA["verde"])
            x += MONO.getlength("  ✓   ")
            d.text((x, y), texto, font=MONO, fill=TEMA["tenue"])
            ancho = MONO.getlength(texto)
        else:
            color = {"comentario": TEMA["gris"], "total": TEMA["verde"], "arranque": TEMA["marca"]}.get(clase, TEMA["tenue"])
            sangrado = "  " + texto if clase in ("salida", "total", "arranque") else texto
            d.text((x, y), sangrado, font=MONO, fill=color)
            ancho = MONO.getlength(sangrado)
        if cursor:
            d.rectangle([x + ancho + 1, y + 2, x + ancho + 10, y + 19], fill=TEMA["marca"])
        y += INTERLINEA

    for i in range(hasta):
        clase, texto = GUION[i]
        linea(clase, texto, False)
    if hasta < len(GUION):
        clase, texto = GUION[hasta]
        linea(clase, texto[:escritas], clase == "orden")
    return imagen


def dibuja(nombre: str) -> None:
    global TEMA, ISOTIPO
    TEMA = TEMAS[nombre]
    ISOTIPO = Image.open(AQUI / "marca" / TEMA["isotipo"]).convert("RGBA")
    destino = AQUI / TEMA["destino"]

    fotogramas, tiempos = [], []
    for i, (clase, texto) in enumerate(GUION):
        if clase == "orden":
            for n in range(0, len(texto) + 1, 3):        # se teclea
                fotogramas.append(pintar(i, n))
                tiempos.append(45)
            tiempos[-1] = 420                            # y se piensa antes de responder
        else:
            fotogramas.append(pintar(i + 1, 0))
            tiempos.append(90 if clase == "blanco" else 260)

    fotogramas.append(pintar(len(GUION), 0))
    tiempos.append(3200)                                 # se queda quieto para poder leerlo

    fotogramas[0].save(destino, save_all=True, append_images=fotogramas[1:],
                       duration=tiempos, loop=0, optimize=True)
    print(f"{destino}  {len(fotogramas)} fotogramas  {destino.stat().st_size / 1024:.0f} KB")


def main() -> int:
    for nombre in TEMAS:
        dibuja(nombre)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
