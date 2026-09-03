#!/usr/bin/env python3
"""Lleva una aplicación a Cero 0.5.0, venga de LuxCore 0.2.x/0.3.x o de Corvo 0.4.0.

Toca cuatro cosas, que son las cuatro que cambiaron de nombre:

  1. Los imports y las referencias con nombre completo: lux.core / corvo.core → cero.core
  2. La clase de arranque: Lux.run(...) / Corvo.run(...) → Cero.run(...)
  3. Las coordenadas de Maven: lux:lux-core:0.3.0 o dev.ginit.corvo:corvo-core:0.4.0
     → dev.ginit.cero:cero-core:0.5.0
  4. Las claves de configuración: lux.* / corvo.* → cero.* y LUX_* / CORVO_* → CERO_*

Acepta los dos nombres viejos a la vez, así que una app que se quedó en LuxCore sin pasar
por Corvo llega a Cero en un solo paso.

No hay capa de compatibilidad en el framework a propósito: arrastrarla significaría llevar
código muerto para siempre. Es más limpio convertir las aplicaciones una vez.

    ./cero migrar ../mi-app --probar    enseña qué cambiaría, sin tocar nada
    ./cero migrar ../mi-app             lo aplica

Lo que el guion NO puede hacer y hay que hacer a mano antes de arrancar:

  * `ALTER TABLE lux_migraciones RENAME TO cero_migraciones;` —o desde corvo_migraciones—.
    Sin eso, Cero cree que no se aplicó ninguna migración y las corre todas otra vez.
  * La cookie de sesión pasa a CEROSESSION: el despliegue cierra todas las sesiones abiertas.
  * Las métricas pasan de lux_* / corvo_* a cero_*: los paneles de Grafana se quedan vacíos
    sin dar ningún error.

Usa el git de la aplicación como red: `git diff` enseña todo y `git checkout .` lo deshace.
"""

import pathlib
import re
import sys

# Los subpaquetes reales del framework. Se listan a mano para no confundir `lux.core`
# (paquete) con `lux.oauth.google.id` (clave de configuración), que se trata aparte.
SUBPAQUETES = "core|http|view|data|adapter|launcher|web"

# Los dos nombres viejos, en un solo patrón: una app puede venir de cualquiera de los dos.
VIEJO = "lux|corvo"

PAQUETE = re.compile(r"\b(?:" + VIEJO + r")\.(" + SUBPAQUETES + r")\b")
ARTEFACTO = re.compile(r"<artifactId>(?:lux|corvo)-([a-z-]+)</artifactId>")
CLASE = re.compile(r"\b(?:Lux|Corvo)\b(?!Servlet)")
CLASE_SERVLET = re.compile(r"\b(?:Lux|Corvo)Servlet\b")

# Claves de configuración. El prefijo cambia; el resto de la clave no.
CLAVE_PROP = re.compile(
    r"\b(?:" + VIEJO + r")\.(?!(?:" + SUBPAQUETES + r")\b)([a-zA-Z0-9_.]+)")
CLAVE_ENV = re.compile(r"\b(?:LUX|CORVO)_([A-Z0-9_]+)")

CODIGO = {".java"}
CONFIG = {".properties", ".yml", ".yaml", ".env", ".conf"}
SALTAR = {"target", "build", ".git", "node_modules", ".idea", ".m2"}


def migrar_texto(ruta: pathlib.Path, texto: str) -> tuple[str, list[str]]:
    """Devuelve el texto convertido y la lista de qué se tocó."""
    notas = []
    original = texto

    if ruta.suffix in CODIGO:
        texto, n = PAQUETE.subn(r"cero.\1", texto)
        if n:
            notas.append(f"{n} referencia(s) de paquete")
        texto, n = CLASE_SERVLET.subn("CeroServlet", texto)
        if n:
            notas.append(f"{n} servlet(s)")
        texto, n = CLASE.subn("Cero", texto)
        if n:
            notas.append(f"{n} clase de arranque")

    if ruta.name == "pom.xml":
        texto = texto.replace("<groupId>lux</groupId>", "<groupId>dev.ginit.cero</groupId>")
        texto = texto.replace("<groupId>dev.ginit.corvo</groupId>", "<groupId>dev.ginit.cero</groupId>")
        texto, n = ARTEFACTO.subn(r"<artifactId>cero-\1</artifactId>", texto)
        if n:
            notas.append(f"{n} artefacto(s)")
        for viejo in ("lux", "corvo"):
            texto = texto.replace(f"<{viejo}.version>", "<cero.version>")
            texto = texto.replace(f"</{viejo}.version>", "</cero.version>")
            texto = texto.replace(f"${{{viejo}.version}}", "${cero.version}")
        # Las versiones viejas del framework pasan a 0.5.0; las de terceros no se tocan.
        texto = re.sub(
            r"(<cero\.version>)0\.[0-4]\.\d+(</cero\.version>)", r"\g<1>0.5.0\g<2>", texto)
        texto, n = PAQUETE.subn(r"cero.\1", texto)
        if n:
            notas.append(f"{n} clase(s) en el pom")

    if ruta.suffix in CONFIG or ruta.name == "pom.xml":
        texto, n = CLAVE_PROP.subn(r"cero.\1", texto)
        if n:
            notas.append(f"{n} clave(s) de configuración")
        texto, n = CLAVE_ENV.subn(r"CERO_\1", texto)
        if n:
            notas.append(f"{n} variable(s) de entorno")

    return (texto, notas) if texto != original else (original, [])


def main() -> int:
    if len(sys.argv) < 2:
        print("uso: migrar.py <ruta-de-la-app> [--probar]", file=sys.stderr)
        return 1
    raiz = pathlib.Path(sys.argv[1]).resolve()
    ensayo = "--probar" in sys.argv or "--dry-run" in sys.argv

    candidatos = []
    for f in raiz.rglob("*"):
        if not f.is_file():
            continue
        if any(parte in SALTAR for parte in f.parts):
            continue
        if f.suffix in CODIGO or f.suffix in CONFIG or f.name == "pom.xml":
            candidatos.append(f)

    if not candidatos:
        print(f"no encontré archivos que migrar en {raiz}", file=sys.stderr)
        return 1

    cambiados, total_notas = [], 0
    for f in candidatos:
        try:
            texto = f.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        nuevo, notas = migrar_texto(f, texto)
        if not notas:
            continue
        cambiados.append((f, notas))
        total_notas += len(notas)
        if not ensayo:
            f.write_text(nuevo, encoding="utf-8")

    cabecera = "cambiaría" if ensayo else "cambiado"
    print(f"\n  {len(cambiados)} archivo(s) {cabecera} en {raiz.name}\n")
    for f, notas in cambiados[:40]:
        print(f"    {f.relative_to(raiz)}")
        print(f"        {', '.join(notas)}")
    if len(cambiados) > 40:
        print(f"    … y {len(cambiados) - 40} más")

    if ensayo:
        print("\n  Ensayo: no se tocó nada. Quita --probar para aplicarlo.\n")
    else:
        print("\n  Aplicado. Ahora, dentro de la aplicación:\n")
        print("    git diff              # revisa lo que cambió")
        print("    mvn test              # y que siga pasando")
        print("    git checkout .        # si algo no cuadra, se deshace entero\n")
        print("  Ojo con lo que este guion NO puede saber por ti:")
        print("    · la cookie de sesión pasó a llamarse CORVOSESSION — al desplegar,")
        print("      quien estuviera dentro tendrá que volver a entrar")
        print("    · las métricas ahora son corvo_requests_total y /corvo/metrics:")
        print("      hay que actualizar el scrapeo de Prometheus y los paneles\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
