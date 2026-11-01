import hmac
import secrets
from urllib.parse import parse_qs

from cero.respuesta import Respuesta
from cero.ruta import trozos
from cero.sesion import CLAVE_CSRF

SEGUROS = {"GET", "HEAD", "OPTIONS", "TRACE"}
FORMULARIO = "application/x-www-form-urlencoded"


class Cabeceras:

    def __init__(self, enmarcado="DENY", csp=None, hsts="max-age=31536000; includeSubDomains"):
        self.enmarcado = enmarcado
        self.csp = csp
        self.hsts = hsts

    def para(self, segura):
        lista = [("X-Content-Type-Options", "nosniff"),
                 ("Referrer-Policy", "strict-origin-when-cross-origin"),
                 ("Permissions-Policy", "camera=(), microphone=(), geolocation=()")]
        if self.enmarcado:
            lista.append(("X-Frame-Options", self.enmarcado))
        if segura and self.hsts:
            lista.append(("Strict-Transport-Security", self.hsts))
        if self.csp:
            lista.append(("Content-Security-Policy", self.csp))
        return lista


def anadir(respuesta, extra):
    lista = list(respuesta.cabeceras)
    for nombre, valor in extra:
        puesta = next((i for i, (n, _) in enumerate(lista) if n.lower() == nombre.lower()), None)
        if puesta is None:
            lista.append((nombre, valor))
        elif nombre.lower() == "vary":
            lista[puesta] = (lista[puesta][0], f"{lista[puesta][1]}, {valor}")
    return Respuesta(respuesta.estado, lista, respuesta.cuerpo)


class Cors:

    def __init__(self, origenes, *, credenciales=False,
                 metodos=("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"),
                 cabeceras=("Content-Type", "X-CSRF-Token"), max_age=600):
        self.todos = origenes == "*"
        self.origenes = set() if self.todos else set(origenes)
        if credenciales and (self.todos or "*" in self.origenes):
            raise ValueError("CORS con credenciales necesita una lista de orígenes, no el comodín")
        self.credenciales = credenciales
        self.metodos = ", ".join(metodos)
        self.cabeceras = ", ".join(cabeceras)
        self.max_age = max_age

    def decidir(self, verbo, origen, metodo_pedido):
        extra = [("Vary", "Origin")]
        preflight = verbo == "OPTIONS" and origen is not None and metodo_pedido is not None
        if origen is None:
            return None, extra
        if not (self.todos or origen in self.origenes):
            return (403 if preflight else None), extra
        extra.append(("Access-Control-Allow-Origin", "*" if self.todos else origen))
        if self.credenciales:
            extra.append(("Access-Control-Allow-Credentials", "true"))
        if not preflight:
            return None, extra
        extra += [("Access-Control-Allow-Methods", self.metodos),
                  ("Access-Control-Allow-Headers", self.cabeceras),
                  ("Access-Control-Max-Age", str(self.max_age))]
        return 204, extra


class Csrf:

    def __init__(self, exentas=()):
        self.exentas = [trozos(e) for e in exentas]

    def exenta(self, camino):
        partes = trozos(camino)
        return any(partes[:len(e)] == e for e in self.exentas)

    def admite(self, peticion):
        if peticion.verbo in SEGUROS or self.exenta(peticion.camino):
            return True
        sesion = peticion.sesion()
        esperado = sesion.leer(CLAVE_CSRF) if sesion else None
        dado = peticion.cabecera("x-csrf-token") or _campo(peticion, "_csrf")
        if esperado is None or dado is None:
            return False
        return hmac.compare_digest(esperado.encode(), dado.encode())


def _campo(peticion, nombre):
    tipo = (peticion.cabecera("content-type") or "").split(";")[0].strip().lower()
    if tipo != FORMULARIO:
        return None
    campos = parse_qs(peticion.cuerpo.decode("utf-8", "replace"), max_num_fields=1000)
    valores = campos.get(nombre, [])
    return valores[0] if len(valores) == 1 else None


def token_csrf(sesion):
    token = sesion.leer(CLAVE_CSRF)
    if token is None:
        token = secrets.token_urlsafe(32)
        sesion.poner(CLAVE_CSRF, token)
    return token
