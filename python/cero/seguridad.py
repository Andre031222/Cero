from cero.respuesta import Respuesta


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
