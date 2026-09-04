#!/usr/bin/env python3
"""Dibuja instalar.gif: la instalación de Cero escribiéndose sola.

Es la misma terminal que la del sitio, pero como imagen, porque GitHub no ejecuta
JavaScript en el README. Se regenera cuando cambien las cifras:

    python3 docs/imagenes/instalar.py
"""

import pathlib

from PIL import Image, ImageDraw, ImageFont

AQUI = pathlib.Path(__file__).resolve().parent
DESTINO = AQUI / "instalar.gif"

ANCHO = 1000
MARGEN = 22
BARRA = 42
INTERLINEA = 27
SANGRIA = 26

FONDO = (7, 11, 20)
PANEL = (13, 20, 36)
BORDE = (34, 46, 74)
TINTA = (234, 240, 250)
TENUE = (147, 160, 188)
GRIS = (110, 123, 153)
MARCA = (255, 61, 154)
VERDE = (63, 196, 140)

MONO = ImageFont.truetype("/System/Library/Fonts/Menlo.ttc", 17)
MONO_FINA = ImageFont.truetype("/System/Library/Fonts/Menlo.ttc", 13)

# (clase, texto). orden = tipo de línea: como se pinta y si se teclea.
GUION = [
    ("comentario", "# 1 — instalar"),
    ("orden",      "curl -fsSL https://cero.ginit.dev/instalar | sh"),
    ("ok",         "entorno    Java 25 · Maven 3.9 · Darwin arm64"),
    ("ok",         "descargado cero-0.5.0.tar.gz · 736 KB"),
    ("ok",         "huella     sha256 d4311facf0aed62a…"),
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

ALTO = MARGEN * 2 + BARRA + 20 + len(GUION) * INTERLINEA + 22


def logo(dibujo: ImageDraw.ImageDraw, cx: float, cy: float, r: float) -> None:
    """El mismo sol de ocho rayos de la marca, a mano: aquí no hay SVG."""
    import math
    for i in range(8):
        angulo = math.radians(i * 45)
        dx, dy = math.cos(angulo), math.sin(angulo)
        dibujo.line([(cx + dx * r * 0.52, cy + dy * r * 0.52),
                     (cx + dx * r, cy + dy * r)], fill=MARCA, width=2)
    dibujo.ellipse([cx - r * 0.26, cy - r * 0.26, cx + r * 0.26, cy + r * 0.26], fill=MARCA)


def lienzo() -> Image.Image:
    imagen = Image.new("RGB", (ANCHO, ALTO), FONDO)
    d = ImageDraw.Draw(imagen)

    d.rounded_rectangle([MARGEN, MARGEN, ANCHO - MARGEN, ALTO - MARGEN],
                        radius=11, fill=PANEL, outline=BORDE, width=1)
    d.line([(MARGEN + 1, MARGEN + BARRA), (ANCHO - MARGEN - 1, MARGEN + BARRA)], fill=BORDE)

    centro = MARGEN + BARRA / 2
    for i, color in enumerate([(255, 95, 87), (254, 188, 46), (40, 200, 64)]):
        x = MARGEN + 20 + i * 19
        d.ellipse([x, centro - 5.5, x + 11, centro + 5.5], fill=color)

    logo(d, MARGEN + 92, centro, 9)
    d.text((MARGEN + 108, centro - 8), "Cero", font=MONO, fill=TINTA)
    d.text((ANCHO / 2 + 40, centro - 6), "~/proyectos", font=MONO_FINA, fill=GRIS,
           anchor="mm")
    return imagen


def pintar(hasta: int, escritas: int) -> Image.Image:
    """Fotograma con las líneas 0..hasta-1 completas y `escritas` letras de la siguiente."""
    imagen = lienzo()
    d = ImageDraw.Draw(imagen)
    y = MARGEN + BARRA + 18

    def linea(clase: str, texto: str, cursor: bool) -> None:
        nonlocal y
        x = MARGEN + SANGRIA
        if clase == "orden":
            d.text((x, y), "$", font=MONO, fill=MARCA)
            x += MONO.getlength("$ ")
            d.text((x, y), texto, font=MONO, fill=TINTA)
            ancho = MONO.getlength(texto)
        elif clase == "ok":
            d.text((x + MONO.getlength("  "), y), "✓", font=MONO, fill=VERDE)
            x += MONO.getlength("  ✓   ")
            d.text((x, y), texto, font=MONO, fill=TENUE)
            ancho = MONO.getlength(texto)
        else:
            color = {"comentario": GRIS, "total": VERDE, "arranque": MARCA}.get(clase, TENUE)
            sangrado = "  " + texto if clase in ("salida", "total", "arranque") else texto
            d.text((x, y), sangrado, font=MONO, fill=color)
            ancho = MONO.getlength(sangrado)
        if cursor:
            d.rectangle([x + ancho + 1, y + 2, x + ancho + 10, y + 19], fill=MARCA)
        y += INTERLINEA

    for i in range(hasta):
        clase, texto = GUION[i]
        linea(clase, texto, False)
    if hasta < len(GUION):
        clase, texto = GUION[hasta]
        linea(clase, texto[:escritas], clase == "orden")
    return imagen


def main() -> int:
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

    fotogramas[0].save(DESTINO, save_all=True, append_images=fotogramas[1:],
                       duration=tiempos, loop=0, optimize=True)
    print(f"{DESTINO}  {len(fotogramas)} fotogramas  {DESTINO.stat().st_size / 1024:.0f} KB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
