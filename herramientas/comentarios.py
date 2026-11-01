#!/usr/bin/env python3
import ast
import io
import os
import re
import sys
import tokenize

MARCA = "\x00"
IGNORADOS = {"target", "build", "node_modules", ".git"}
LENGUAJES = {
    ".java": "java",
    ".rs": "rust",
    ".cpp": "cpp",
    ".hpp": "cpp",
    ".h": "cpp",
    ".py": "python",
}
IDENTIFICADOR = re.compile(r"\w+")
CRUDA_RUST = re.compile(r'(#*)"')
PREFIJOS_CRUDA_CPP = {"R", "u8R", "uR", "UR", "LR"}
CODIFICACION = re.compile(r"^#.*coding[:=]\s*[-\w.]+")


def fin_de_cadena(s, i, comilla, multilinea):
    n = len(s)
    j = i + 1
    while j < n:
        c = s[j]
        if c == "\\":
            j += 2
            continue
        if c == comilla:
            return j + 1
        if c == "\n" and not multilinea:
            return None
        j += 1
    return None if not multilinea else n


def fin_de_comentario_de_linea(s, i, lenguaje):
    n = len(s)
    fin = s.find("\n", i)
    if fin == -1:
        return n
    if lenguaje == "cpp":
        while fin > 0 and s[fin - 1] == "\\":
            siguiente = s.find("\n", fin + 1)
            if siguiente == -1:
                return n
            fin = siguiente
    return fin


def fin_de_comentario_de_bloque(s, i, lenguaje):
    n = len(s)
    if lenguaje != "rust":
        fin = s.find("*/", i + 2)
        return n if fin == -1 else fin + 2
    profundidad = 1
    j = i + 2
    while j < n:
        if s.startswith("/*", j):
            profundidad += 1
            j += 2
        elif s.startswith("*/", j):
            profundidad -= 1
            j += 2
            if profundidad == 0:
                return j
        else:
            j += 1
    return n


def es_lifetime_rust(s, i):
    n = len(s)
    if i + 1 >= n or s[i + 1] == "\\":
        return False
    if i + 2 < n and s[i + 2] == "'":
        return False
    return True


def quitar_familia_c(s, lenguaje):
    salida = []
    encontrados = []
    n = len(s)
    i = 0
    linea = 1
    ultimo = ("", -1)
    while i < n:
        c = s[i]
        if c == "\n":
            salida.append(c)
            linea += 1
            i += 1
            continue
        if c.isalnum() or c == "_":
            m = IDENTIFICADOR.match(s, i)
            palabra = m.group()
            j = m.end()
            fin = None
            if lenguaje == "rust" and palabra in ("r", "br", "cr"):
                m2 = CRUDA_RUST.match(s, j)
                if m2:
                    cierre = '"' + m2.group(1)
                    pos = s.find(cierre, m2.end())
                    fin = n if pos == -1 else pos + len(cierre)
            elif lenguaje == "cpp" and palabra in PREFIJOS_CRUDA_CPP and s.startswith('"', j):
                p = s.find("(", j + 1)
                if p != -1:
                    cierre = ")" + s[j + 1 : p] + '"'
                    pos = s.find(cierre, p)
                    fin = n if pos == -1 else pos + len(cierre)
            if fin is not None:
                trozo = s[i:fin]
                salida.append(trozo)
                linea += trozo.count("\n")
                i = fin
                continue
            salida.append(palabra)
            ultimo = (palabra, j)
            i = j
            continue
        if c == '"':
            if lenguaje == "java" and s.startswith('"""', i):
                j = i + 3
                while j < n and not s.startswith('"""', j):
                    j += 2 if s[j] == "\\" else 1
                fin = min(n, j + 3)
            else:
                fin = fin_de_cadena(s, i, '"', lenguaje == "rust")
                if fin is None:
                    fin = i + 1
            trozo = s[i:fin]
            salida.append(trozo)
            linea += trozo.count("\n")
            i = fin
            continue
        if c == "'":
            if lenguaje == "rust" and es_lifetime_rust(s, i):
                salida.append(c)
                i += 1
                continue
            if lenguaje == "cpp" and ultimo[1] == i and ultimo[0][:1].isdigit():
                salida.append(c)
                i += 1
                continue
            fin = fin_de_cadena(s, i, "'", False)
            if fin is None:
                fin = i + 1
            salida.append(s[i:fin])
            i = fin
            continue
        if c == "/" and s.startswith("//", i):
            fin = fin_de_comentario_de_linea(s, i, lenguaje)
            encontrados.append(linea)
            linea += s.count("\n", i, fin)
            salida.append(MARCA)
            i = fin
            continue
        if c == "/" and s.startswith("/*", i):
            fin = fin_de_comentario_de_bloque(s, i, lenguaje)
            encontrados.append(linea)
            linea += s.count("\n", i, fin)
            salida.append(MARCA)
            i = fin
            continue
        salida.append(c)
        i += 1
    return "".join(salida), encontrados


def posicion_en_caracteres(linea, desplazamiento_bytes):
    return len(linea.encode("utf-8")[:desplazamiento_bytes].decode("utf-8", "ignore"))


def docstrings(arbol):
    encontrados = []
    for nodo in ast.walk(arbol):
        if not isinstance(
            nodo, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)
        ):
            continue
        cuerpo = nodo.body
        if not cuerpo:
            continue
        primero = cuerpo[0]
        if (
            isinstance(primero, ast.Expr)
            and isinstance(primero.value, ast.Constant)
            and isinstance(primero.value.value, str)
        ):
            encontrados.append((primero, len(cuerpo) == 1 and not isinstance(nodo, ast.Module)))
    return encontrados


def quitar_python(s):
    lineas = s.split("\n")
    ediciones = []
    encontrados = []
    for tipo, texto, inicio, fin, _ in tokenize.generate_tokens(io.StringIO(s).readline):
        if tipo != tokenize.COMMENT:
            continue
        if inicio[0] == 1 and texto.startswith("#!"):
            continue
        if inicio[0] <= 2 and CODIFICACION.match(texto):
            continue
        ediciones.append((inicio[0], inicio[1], fin[0], fin[1], MARCA))
        encontrados.append(inicio[0])
    for sentencia, unica in docstrings(ast.parse(s)):
        l1, l2 = sentencia.lineno, sentencia.end_lineno
        c1 = posicion_en_caracteres(lineas[l1 - 1], sentencia.col_offset)
        c2 = posicion_en_caracteres(lineas[l2 - 1], sentencia.end_col_offset)
        ediciones.append((l1, c1, l2, c2, "pass" if unica else MARCA))
        encontrados.append(l1)
    for l1, c1, l2, c2, reemplazo in sorted(ediciones, reverse=True):
        lineas[l1 - 1 : l2] = [lineas[l1 - 1][:c1] + reemplazo + lineas[l2 - 1][c2:]]
    return "\n".join(lineas), sorted(encontrados)


def compactar(s):
    resultado = []
    pendiente = False
    for linea in s.split("\n"):
        if MARCA not in linea:
            if linea.strip() == "":
                if pendiente and (not resultado or resultado[-1].strip() == ""):
                    pendiente = False
                    continue
                pendiente = False
            else:
                pendiente = False
            resultado.append(linea)
            continue
        partes = linea.split(MARCA)
        nueva = partes[0]
        for parte in partes[1:]:
            if nueva.strip() == "" or (nueva and nueva[-1].isspace()):
                parte = parte.lstrip()
            elif parte and not parte[0].isspace():
                nueva += " "
            nueva += parte
        nueva = nueva.rstrip()
        if nueva.strip() == "":
            pendiente = True
            continue
        pendiente = False
        resultado.append(nueva)
    return "\n".join(resultado)


def procesar(texto, extension):
    sustituto = None
    if MARCA in texto:
        sustituto = next(chr(c) for c in range(0xE000, 0xF900) if chr(c) not in texto)
        texto = texto.replace(MARCA, sustituto)
    limpio, encontrados = procesar_sin_nulos(texto, extension)
    if sustituto:
        limpio = limpio.replace(sustituto, MARCA)
    return limpio, encontrados


def procesar_sin_nulos(texto, extension):
    lenguaje = LENGUAJES[extension]
    fin_windows = "\r\n" in texto
    if fin_windows:
        texto = texto.replace("\r\n", "\n")
    if lenguaje == "python":
        marcado, encontrados = quitar_python(texto)
    else:
        marcado, encontrados = quitar_familia_c(texto, lenguaje)
    limpio = compactar(marcado) if encontrados else texto
    if fin_windows:
        limpio = limpio.replace("\n", "\r\n")
    return limpio, encontrados


def archivos(rutas):
    for ruta in rutas:
        if os.path.isfile(ruta):
            if os.path.splitext(ruta)[1] in LENGUAJES:
                yield ruta
            continue
        for carpeta, subcarpetas, nombres in os.walk(ruta):
            subcarpetas[:] = sorted(d for d in subcarpetas if d not in IGNORADOS)
            for nombre in sorted(nombres):
                if os.path.splitext(nombre)[1] in LENGUAJES:
                    yield os.path.join(carpeta, nombre)


def leer(ruta):
    with open(ruta, encoding="utf-8", newline="") as f:
        return f.read()


def principal(argumentos):
    if len(argumentos) < 2 or argumentos[0] not in ("--quitar", "--verificar"):
        print("uso: comentarios.py (--quitar | --verificar) <ruta>...", file=sys.stderr)
        return 2
    quitando = argumentos[0] == "--quitar"
    total = 0
    fallos = 0
    for ruta in archivos(argumentos[1:]):
        try:
            texto = leer(ruta)
            limpio, encontrados = procesar(texto, os.path.splitext(ruta)[1])
        except (OSError, UnicodeDecodeError, SyntaxError, tokenize.TokenError) as error:
            print(f"{ruta}: no se pudo procesar ({error})", file=sys.stderr)
            fallos += 1
            continue
        if quitando:
            if limpio != texto:
                with open(ruta, "w", encoding="utf-8", newline="") as f:
                    f.write(limpio)
                print(f"{ruta}: {len(encontrados)}")
        else:
            for linea in encontrados:
                print(f"{ruta}:{linea}")
        total += len(encontrados)
    if fallos:
        return 2
    if not quitando and total:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(principal(sys.argv[1:]))
