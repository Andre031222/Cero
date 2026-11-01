import sys


def comprobar_version(version=sys.version_info):
    if tuple(version[:2]) < (3, 12):
        actual = ".".join(str(n) for n in version[:3])
        raise RuntimeError(f"Cero necesita Python 3.12 o posterior, y este es {actual}")


comprobar_version()
