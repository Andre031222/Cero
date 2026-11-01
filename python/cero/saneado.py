import re
from html import escape
from html.parser import HTMLParser

PERMITIDAS = {"p", "b", "i", "em", "strong", "a", "ul", "ol", "li", "br", "code", "pre",
              "blockquote", "div", "span"}
ATRIBUTOS = {"a": {"href", "title"}}
ESQUEMAS = {"http", "https", "mailto"}
CON_CONTENIDO = {"script", "style", "template", "iframe", "frame", "frameset", "object", "embed",
                 "applet", "svg", "math", "noscript", "noembed", "noframes", "xmp", "plaintext",
                 "textarea", "select", "title"}
VACIAS = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source",
          "track", "wbr", "frame"}
INVISIBLE = re.compile(r"[\x00-\x20\x7f]+")
ESQUEMA = re.compile(r"([a-z][a-z0-9+.-]*):")
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
        if etiqueta in CON_CONTENIDO or self.dentro:
            if etiqueta in CON_CONTENIDO and etiqueta not in VACIAS and not cierre:
                self.dentro += 1
            return
        if self.con_marcado and etiqueta in PERMITIDAS:
            partes = [etiqueta, *_atributos(etiqueta, atributos)]
            self.salida.append("<" + " ".join(partes) + ("/>" if cierre else ">"))

    def handle_starttag(self, etiqueta, atributos):
        self._abrir(etiqueta, atributos, False)

    def handle_startendtag(self, etiqueta, atributos):
        self._abrir(etiqueta, atributos, True)

    def handle_endtag(self, etiqueta):
        if self.dentro:
            if etiqueta in CON_CONTENIDO:
                self.dentro -= 1
            return
        if self.con_marcado and etiqueta in PERMITIDAS and etiqueta not in VACIAS:
            self.salida.append(f"</{etiqueta}>")

    def handle_data(self, datos):
        if not self.dentro:
            self.salida.append(escape(datos, quote=False))


def _enlace_seguro(valor):
    esquema = ESQUEMA.match(INVISIBLE.sub("", valor).lower())
    return esquema is None or esquema.group(1) in ESQUEMAS


def _atributos(etiqueta, atributos):
    admitidos = ATRIBUTOS.get(etiqueta, set())
    for nombre, valor in atributos:
        if nombre not in admitidos or valor is None:
            continue
        if nombre == "href" and not _enlace_seguro(valor):
            continue
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
