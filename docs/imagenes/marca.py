#!/usr/bin/env python3
"""Dibuja la imagen de marca: la portada del README y la tarjeta social.

Son el mismo dibujo en dos sitios —`docs/imagenes/portada.png` y
`docs/web/assets/social.png`—, así que salen del mismo guion y no pueden decir
cosas distintas. Cuando cambien las cifras:

    python3 docs/imagenes/marca.py
"""

import math
import pathlib

from PIL import Image, ImageDraw, ImageFont

AQUI = pathlib.Path(__file__).resolve().parent
DESTINOS = [AQUI / "portada.png", AQUI.parent / "web" / "assets" / "social.png"]

ANCHO, ALTO = 1200, 630

FONDO = (14, 16, 21)
TINTA = (242, 240, 236)
TENUE = (140, 144, 152)
MARCA = (212, 160, 62)

NEUE = "/System/Library/Fonts/HelveticaNeue.ttc"
MONO = "/System/Library/Fonts/Menlo.ttc"


def fuente(ruta: str, tamano: int, negrita: bool = False) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(ruta, tamano, index=1 if negrita else 0)


TITULO   = fuente(NEUE, 82, negrita=True)
CIFRA    = fuente(NEUE, 62, negrita=True)
UNIDAD   = fuente(NEUE, 27, negrita=True)
CUERPO   = fuente(NEUE, 27)
ETIQUETA = fuente(MONO, 19, negrita=True)
BAJADA   = fuente(MONO, 18, negrita=True)

# cifra, unidad, pie, ¿la cifra va en dorado?
MEDIDAS = [
    ("106", "ms",   "ARRANQUE",       True),
    ("0",   "deps", "EN EJECUCIÓN",   False),
    ("308", "KB",   "CUATRO MÓDULOS", False),
]

LEMA = ["Arranca solo, sin contenedor de servlets",
        "y sin una sola dependencia externa."]


def espaciado(texto: str, separacion: str = " ") -> str:
    """El interletrado de las etiquetas, que PIL no sabe hacer solo."""
    return separacion.join(texto)


def sol(d: ImageDraw.ImageDraw, cx: float, cy: float, r: float) -> None:
    """El logo: dieciséis rayos de dos largos alternos y un disco al centro."""
    for i in range(16):
        angulo = math.radians(i * 22.5)
        dx, dy = math.cos(angulo), math.sin(angulo)
        largo = r if i % 2 == 0 else r * 0.78
        d.line([(cx + dx * r * 0.46, cy + dy * r * 0.46), (cx + dx * largo, cy + dy * largo)],
               fill=MARCA, width=7)
    d.ellipse([cx - r * 0.25, cy - r * 0.25, cx + r * 0.25, cy + r * 0.25], fill=MARCA)


def main() -> int:
    imagen = Image.new("RGB", (ANCHO, ALTO), FONDO)
    d = ImageDraw.Draw(imagen)

    d.rectangle([0, 0, ANCHO, 5], fill=MARCA)
    sol(d, 180, 227, 62)

    # ─── columna izquierda: la marca ───
    x = 96
    d.text((x, 318), "Lux", font=TITULO, fill=TINTA)
    d.text((x + TITULO.getlength("Lux"), 318), "Core", font=TITULO, fill=MARCA)
    d.text((x + 2, 421), espaciado("FRAMEWORK WEB PARA JAVA"), font=ETIQUETA, fill=TENUE)
    for i, linea in enumerate(LEMA):
        d.text((x, 484 + i * 44), linea, font=CUERPO, fill=TINTA)

    # ─── columna derecha: las tres cifras, alineadas al mismo borde ───
    borde = ANCHO - 96
    y = 246
    for numero, unidad, pie, dorada in MEDIDAS:
        ancho_unidad = UNIDAD.getlength(unidad) + 11    # el aire entre cifra y unidad
        d.text((borde, y), unidad, font=UNIDAD, fill=TENUE, anchor="rt")
        d.text((borde - ancho_unidad, y - 8), numero, font=CIFRA,
               fill=MARCA if dorada else TINTA, anchor="rt")
        d.text((borde, y + 62), espaciado(pie), font=BAJADA, fill=TENUE, anchor="rt")
        y += 132

    for destino in DESTINOS:
        imagen.save(destino, optimize=True)
        print(f"  {destino}  {destino.stat().st_size / 1024:.0f} KB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
