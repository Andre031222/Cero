import json
import re
from dataclasses import dataclass

from cero.respuesta import Respuesta, texto

NOMBRES = {str: "texto", int: "un entero", float: "un número", bool: "verdadero o falso",
           list: "una lista", dict: "un objeto"}


@dataclass(frozen=True)
class Campo:
    tipo: type = str
    requerido: bool = True
    minimo: object = None
    maximo: object = None
    patron: str = None

    def motivo(self, valor):
        if valor is None or (self.tipo is str and isinstance(valor, str) and not valor.strip()):
            return "es obligatorio" if self.requerido else None
        if not _es(valor, self.tipo):
            return f"debe ser {NOMBRES.get(self.tipo, self.tipo.__name__)}"
        medida, unidad = (len(valor), " caracteres") if self.tipo is str else (valor, "")
        if self.tipo in (str, int, float):
            if self.minimo is not None and medida < self.minimo:
                return f"debe {'tener' if unidad else 'ser'} al menos {self.minimo}{unidad}"
            if self.maximo is not None and medida > self.maximo:
                return f"debe {'tener' if unidad else 'ser'} como mucho {self.maximo}{unidad}"
        if self.patron is not None and self.tipo is str and not re.fullmatch(self.patron, valor):
            return "no tiene el formato esperado"
        return None


def _es(valor, tipo):
    if isinstance(valor, bool):
        return tipo is bool
    if tipo is float:
        return isinstance(valor, (int, float))
    return isinstance(valor, tipo)


def _sin_repetidas(pares):
    objeto = dict(pares)
    if len(objeto) != len(pares):
        raise ValueError("clave repetida")
    return objeto


def _rechazar_constante(nombre):
    raise ValueError(f"{nombre} no es JSON")


def _leer(peticion):
    tipo = (peticion.cabecera("content-type") or "").split(";")[0].strip().lower()
    if tipo != "application/json":
        return texto(415, "el cuerpo debe ser application/json")
    try:
        return json.loads(peticion.cuerpo.decode("utf-8"), object_pairs_hook=_sin_repetidas,
                          parse_constant=_rechazar_constante)
    except (ValueError, RecursionError):
        return texto(400, "el cuerpo no es JSON válido")


def _invalido(campos, error="el cuerpo no es válido"):
    cuerpo = json.dumps({"error": error, "campos": campos}, ensure_ascii=False).encode()
    return Respuesta(422, [("Content-Type", "application/json")], cuerpo)


def validado(esquema, accion):
    def envuelta(peticion):
        datos = _leer(peticion)
        if isinstance(datos, Respuesta):
            return datos
        if not isinstance(datos, dict):
            return _invalido({}, "el cuerpo debe ser un objeto JSON")
        campos = {n: m for n, c in esquema.items() if (m := c.motivo(datos.get(n)))}
        campos |= {n: "no se admite" for n in datos if n not in esquema}
        if campos:
            return _invalido(campos)
        peticion.datos = datos
        return accion(peticion)
    return envuelta
