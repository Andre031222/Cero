#!/usr/bin/env python3
"""Dibuja banco.png a partir del CSV del banco, en los dos temas.

La imagen anterior se quedó cuatro versiones atrás —seguía diciendo «LuxCore» y
citando cifras de agosto— porque se dibujó a mano y no había forma de rehacerla.
Esta lee `benchmarks/results/raw-docker.csv`, así que no puede desviarse de lo medido:

    python3 docs/imagenes/banco.py
"""

import csv
import pathlib
import statistics

from PIL import Image, ImageDraw, ImageFont

AQUI = pathlib.Path(__file__).resolve().parent
CSV = AQUI / ".." / ".." / "benchmarks" / "results" / "raw-docker.csv"

NOMBRES = {"cero": "Cero", "spring": "Spring Boot", "quarkus": "Quarkus",
           "micronaut": "Micronaut", "javalin": "Javalin", "helidon": "Helidon",
           "vertx": "Vert.x", "jooby": "Jooby", "jxmvc": "JxMVC"}

TEMAS = {
    "oscuro": dict(destino="banco.png", fondo=(6, 6, 7), panel=(19, 19, 21),
                   linea=(38, 38, 42), tinta=(242, 245, 250), tenue=(135, 141, 153),
                   barra=(58, 58, 66), marca=(56, 189, 248)),
    "claro": dict(destino="banco-claro.png", fondo=(247, 249, 252), panel=(234, 238, 246),
                  linea=(214, 220, 230), tinta=(15, 20, 28), tenue=(97, 108, 135),
                  barra=(202, 210, 222), marca=(29, 78, 216)),
}

ANCHO, MARGEN, FILA = 1200, 40, 30
MONO = ImageFont.truetype("/System/Library/Fonts/Menlo.ttc", 14)
MONO_FINA = ImageFont.truetype("/System/Library/Fonts/Menlo.ttc", 11)
NEGRITA = ImageFont.truetype("/System/Library/Fonts/Menlo.ttc", 17)


def medianas():
    """Mediana por framework y métrica. El CSV trae una fila por repetición."""
    crudo = {}
    with open(CSV, newline="") as f:
        for r in csv.DictReader(f):
            d = crudo.setdefault(r["framework"], {"arranque": [], "rss": [], "db": []})
            d["arranque"].append(float(r["startup_ms"]))
            d["rss"].append(float(r["rss_mb"]))
            if r["endpoint"].endswith("/db"):
                d["db"].append(float(r["rps"]))
    return {k: {m: statistics.median(v) for m, v in d.items() if v} for k, d in crudo.items()}


def panel(d, t, x, y, ancho, titulo, pie, datos, fmt, mejor_es_menor):
    d.text((x, y), titulo, font=MONO_FINA, fill=t["tenue"])
    d.text((x, y + 16), pie, font=MONO_FINA, fill=t["tenue"])
    y += 40
    orden = sorted(datos.items(), key=lambda kv: kv[1], reverse=not mejor_es_menor)
    tope = max(datos.values())
    # «Spring Boot» es el rótulo más largo: si la columna no le llega, pisa la barra.
    rotulo = 8 + max(MONO.getlength(NOMBRES[k]) for k in datos)
    cifra = 86
    for nombre, valor in orden:
        cero = nombre == "cero"
        color = t["marca"] if cero else t["barra"]
        d.text((x, y + 4), NOMBRES[nombre], font=MONO, fill=t["tinta"] if cero else t["tenue"])
        largo = max(3, int((ancho - rotulo - cifra) * valor / tope))
        d.rounded_rectangle([x + rotulo, y + 2, x + rotulo + largo, y + 18], radius=3, fill=color)
        d.text((x + rotulo + largo + 8, y + 4), fmt(valor), font=MONO,
               fill=t["marca"] if cero else t["tenue"])
        y += FILA
    return y


def dibuja(nombre, datos, cabecera):
    t = TEMAS[nombre]
    alto = 150 + len(datos) * FILA
    img = Image.new("RGB", (ANCHO, alto), t["fondo"])
    d = ImageDraw.Draw(img)
    d.text((MARGEN, 28), "Cero frente a ocho frameworks JVM", font=NEGRITA, fill=t["tinta"])
    d.text((MARGEN, 52), cabecera, font=MONO_FINA, fill=t["tenue"])

    col = (ANCHO - MARGEN * 2 - 60) // 3
    for i, (titulo, pie, clave, fmt, menor) in enumerate([
        ("ARRANQUE EN FRÍO", "de docker run al primer 200 · menos es mejor",
         "arranque", lambda v: f"{v:.0f} ms", True),
        ("MEMORIA EN ESTADO ESTACIONARIO", "RSS con el mismo -Xmx · menos es mejor",
         "rss", lambda v: f"{v:.0f} MB", True),
        ("PETICIONES POR SEGUNDO EN /db", "SELECT real sobre H2 · más es mejor",
         "db", lambda v: f"{v:,.0f}".replace(",", " "), False),
    ]):
        panel(d, t, MARGEN + i * (col + 30), 90, col, titulo, pie,
              {k: v[clave] for k, v in datos.items() if clave in v}, fmt, menor)

    destino = AQUI / t["destino"]
    img.save(destino, optimize=True)
    print(f"{destino}  {destino.stat().st_size / 1024:.0f} KB")


def main() -> int:
    datos = medianas()
    with open(CSV, newline="") as f:
        primera = next(csv.DictReader(f))
    cabecera = (f"Contenedores idénticos · --cpus=2 --memory=1g · -Xmx256m · "
                f"{primera['conns']} conexiones · {primera['dur']} s · mediana de 5 repeticiones")
    for nombre in TEMAS:
        dibuja(nombre, datos, cabecera)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
