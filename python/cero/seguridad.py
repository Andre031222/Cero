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
    puestas = {n.lower() for n, _ in respuesta.cabeceras}
    nuevas = [(n, v) for n, v in extra if n.lower() not in puestas]
    return Respuesta(respuesta.estado, [*respuesta.cabeceras, *nuevas], respuesta.cuerpo)
