#!/usr/bin/env python3

import pathlib
import re
import sys

SUBPAQUETES = "core|http|view|data|adapter|launcher|web"

VIEJO = "lux|corvo"

PAQUETE = re.compile(r"\b(?:" + VIEJO + r")\.(" + SUBPAQUETES + r")\b")
ARTEFACTO = re.compile(r"<artifactId>(?:lux|corvo)-([a-z-]+)</artifactId>")
CLASE = re.compile(r"\b(?:Lux|Corvo)\b(?!Servlet)")
CLASE_SERVLET = re.compile(r"\b(?:Lux|Corvo)Servlet\b")

CLAVE_PROP = re.compile(
    r"\b(?:" + VIEJO + r")\.(?!(?:" + SUBPAQUETES + r")\b)([a-zA-Z0-9_.]+)")
CLAVE_ENV = re.compile(r"\b(?:LUX|CORVO)_([A-Z0-9_]+)")

VERSION = "0.7.0"
RELEASE = "25"

DEPENDENCIA = re.compile(
    r"(<dependency>(?:(?!</dependency>).)*?<artifactId>cero-[a-z-]+</artifactId>"
    r"(?:(?!</dependency>).)*?<version>)0\.[0-5]\.\d+(</version>)", re.S)

CODIGO = {".java"}
CONFIG = {".properties", ".yml", ".yaml", ".env", ".conf", ".service"}
NOMBRES = {"Makefile", "Caddyfile", "Dockerfile", "Procfile"}
SALTAR = {"target", "build", ".git", "node_modules", ".idea", ".m2"}


def es_config(f: pathlib.Path) -> bool:
    return (f.suffix in CONFIG or f.name in NOMBRES
            or f.name.startswith(".env") or f.name == "pom.xml")


def migrar_texto(ruta: pathlib.Path, texto: str) -> tuple[str, list[str]]:
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
        texto, n = re.subn(
            r"(<cero\.version>)0\.[0-5]\.\d+(</cero\.version>)", rf"\g<1>{VERSION}\g<2>", texto)
        texto, k = DEPENDENCIA.subn(rf"\g<1>{VERSION}\g<2>", texto)
        if n + k:
            notas.append(f"versión del framework a {VERSION}")
        for prop in ("release", "source", "target"):
            texto, n = re.subn(
                rf"(<maven\.compiler\.{prop}>)\d+(</maven\.compiler\.{prop}>)",
                rf"\g<1>{RELEASE}\g<2>", texto)
            if n:
                notas.append(f"maven.compiler.{prop} a {RELEASE}")
        texto, n = PAQUETE.subn(r"cero.\1", texto)
        if n:
            notas.append(f"{n} clase(s) en el pom")

    if es_config(ruta):
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
        if f.suffix in CODIGO or es_config(f):
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
        print("    · la cookie de sesión pasó a llamarse CEROSESSION — al desplegar,")
        print("      quien estuviera dentro tendrá que volver a entrar")
        print("    · las métricas ahora son cero_requests_total y /cero/metrics:")
        print("      hay que actualizar el scrapeo de Prometheus y los paneles\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
