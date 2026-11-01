import pathlib
import shutil
import sys
import tempfile
import zipapp

CERO = pathlib.Path(__file__).resolve().parent
SOBRANTES = shutil.ignore_patterns("__pycache__", "*.pyc", ".*")


def empaquetar(carpeta, salida, entrada=None):
    carpeta = pathlib.Path(carpeta)
    if not carpeta.is_dir():
        raise ValueError(f"{carpeta} no es una carpeta")
    if carpeta.joinpath("cero").exists():
        raise ValueError(f"{carpeta} ya trae una carpeta cero; chocaría con el framework")
    if entrada is None and not carpeta.joinpath("__main__.py").is_file():
        raise ValueError(f"{carpeta} no tiene __main__.py; indica la entrada como modulo:funcion")
    with tempfile.TemporaryDirectory() as tmp:
        raiz = pathlib.Path(tmp, "app")
        shutil.copytree(carpeta, raiz, ignore=SOBRANTES)
        shutil.copytree(CERO, raiz / "cero", ignore=SOBRANTES)
        zipapp.create_archive(raiz, salida, interpreter="/usr/bin/env python3", main=entrada,
                              compressed=True)


def main(argumentos):
    if len(argumentos) not in (2, 3):
        print("uso: python3 -m cero.empaquetar <carpeta-app> <salida.pyz> [modulo:funcion]",
              file=sys.stderr)
        return 2
    try:
        empaquetar(*argumentos)
    except (ValueError, zipapp.ZipAppError, OSError) as fallo:
        print(f"no se pudo empaquetar: {fallo}", file=sys.stderr)
        return 1
    print(f"listo: python3 {argumentos[1]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
