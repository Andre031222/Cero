from dataclasses import dataclass
from typing import Callable


def _trozos(camino):
    limpio = camino.strip("/")
    return limpio.split("/") if limpio else []


class Patron:

    def __init__(self, texto):
        if not texto.startswith("/"):
            raise ValueError(f"el patrón {texto!r} debe empezar por /")
        self.texto = texto
        self.segmentos = tuple(_trozos(texto))
        for i, segmento in enumerate(self.segmentos):
            if segmento == "*":
                if i != len(self.segmentos) - 1:
                    raise ValueError(f"el comodín de {texto!r} solo puede ir al final")
            elif "{" in segmento or "}" in segmento:
                nombre = segmento[1:-1]
                bien = segmento[0] == "{" and segmento[-1] == "}" and nombre
                if not bien or any(c in nombre for c in "{}*"):
                    raise ValueError(f"variable mal formada en {texto!r}: {segmento!r}")
            elif not segmento or "*" in segmento:
                raise ValueError(f"segmento mal formado en {texto!r}: {segmento!r}")

    def casar(self, camino):
        trozos = _trozos(camino)
        comodin = self.segmentos[-1:] == ("*",)
        fijos = self.segmentos[:-1] if comodin else self.segmentos
        if len(trozos) < len(fijos) + comodin or (not comodin and len(trozos) != len(fijos)):
            return None
        variables = {}
        for segmento, trozo in zip(fijos, trozos):
            if segmento.startswith("{"):
                if not trozo:
                    return None
                variables[segmento[1:-1]] = trozo
            elif segmento != trozo:
                return None
        if comodin:
            variables["*"] = "/".join(trozos[len(fijos):])
        return variables

    def peso(self):
        return tuple(2 if s == "*" else 1 if s.startswith("{") else 0 for s in self.segmentos)


@dataclass(frozen=True)
class Resuelto:
    accion: Callable
    variables: dict


@dataclass(frozen=True)
class NoEncontrado:
    pass


@dataclass(frozen=True)
class NoPermitido:
    verbos: list


class Router:

    def __init__(self):
        self.rutas = []

    def agregar(self, verbo, patron, accion):
        patron = Patron(patron)
        if any(v == verbo and p.segmentos == patron.segmentos for v, p, _ in self.rutas):
            raise ValueError(f"ruta duplicada: {verbo} {patron.texto}")
        self.rutas.append((verbo, patron, accion))

    def resolver(self, verbo, camino):
        casan = []
        for v, patron, accion in self.rutas:
            variables = patron.casar(camino)
            if variables is not None:
                casan.append((v, patron, accion, variables))
        if not casan:
            return NoEncontrado()
        for buscado in [verbo, "GET"] if verbo == "HEAD" else [verbo]:
            candidatos = [c for c in casan if c[0] == buscado]
            if candidatos:
                _, _, accion, variables = min(candidatos, key=lambda c: c[1].peso())
                return Resuelto(accion, variables)
        verbos = {c[0] for c in casan}
        if "GET" in verbos:
            verbos.add("HEAD")
        return NoPermitido(sorted(verbos))
