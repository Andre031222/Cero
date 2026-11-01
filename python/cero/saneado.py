import re
from html import escape
from html.parser import HTMLParser

FUERA = {"script", "style", "iframe", "frame", "frameset", "object", "embed", "applet",
         "noscript", "noembed", "noframes", "xmp", "plaintext", "template", "base", "link",
         "meta", "set", "animate", "animatemotion", "animatetransform", "use", "foreignobject",
         "handler", "listener"}
VACIAS = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source",
          "track", "wbr", "frame"}
NOMBRE = re.compile(r"[a-z][a-z0-9]*")
ATRIBUTO = re.compile(r"[a-z][a-z0-9_-]*")
INVISIBLE = re.compile(r"[\x00-\x20\x7f]+")
PROTOCOLOS = ("javascript:", "vbscript:")
RESERVADOS = {"con", "prn", "aux", "nul", *(f"com{i}" for i in range(1, 10)),
              *(f"lpt{i}" for i in range(1, 10))}
SEGURO = re.compile(r"[^A-Za-z0-9._-]")


class _Saneador(HTMLParser):

    def __init__(self, con_marcado):
        super().__init__(convert_charrefs=True)
        self.con_marcado = con_marcado
        self.dentro = 0
        self.salida = []

    def _abrir(self, etiqueta, atributos, cierre):
        if etiqueta in FUERA or self.dentro:
            if etiqueta in FUERA and etiqueta not in VACIAS and not cierre:
                self.dentro += 1
            return
        if self.con_marcado and NOMBRE.fullmatch(etiqueta):
            partes = [etiqueta, *_atributos(atributos)]
            self.salida.append("<" + " ".join(partes) + ("/>" if cierre else ">"))

    def handle_starttag(self, etiqueta, atributos):
        self._abrir(etiqueta, atributos, False)

    def handle_startendtag(self, etiqueta, atributos):
        self._abrir(etiqueta, atributos, True)

    def handle_endtag(self, etiqueta):
        if self.dentro:
            if etiqueta in FUERA:
                self.dentro -= 1
            return
        if self.con_marcado and NOMBRE.fullmatch(etiqueta) and etiqueta not in FUERA:
            self.salida.append(f"</{etiqueta}>")

    def handle_data(self, datos):
        if not self.dentro:
            self.salida.append(escape(datos, quote=False) if self.con_marcado else datos)


def _atributos(atributos):
    for nombre, valor in atributos:
        if not ATRIBUTO.fullmatch(nombre) or nombre.startswith("on"):
            continue
        if valor is None:
            yield nombre
        elif not any(p in INVISIBLE.sub("", valor).lower() for p in PROTOCOLOS):
            yield f'{nombre}="{escape(valor)}"'


def _sanear(entrada, con_marcado):
    saneador = _Saneador(con_marcado)
    saneador.feed(entrada)
    saneador.close()
    return "".join(saneador.salida)


def sanear_html(entrada):
    return _sanear(entrada, True)


def sanear_texto(entrada):
    return _sanear(entrada, False)


def sanear_nombre(entrada):
    base = re.split(r"[/\\]", entrada)[-1]
    limpio = SEGURO.sub("_", base).strip("._")[:255]
    if not limpio:
        return "archivo"
    if limpio.split(".")[0].lower() in RESERVADOS:
        limpio = ("_" + limpio)[:255]
    return limpio
