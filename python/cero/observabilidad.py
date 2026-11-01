import json
import math
import os
import sys
import threading
import time
import traceback
from collections import deque
from datetime import datetime, timezone

from cero.respuesta import Respuesta
from cero.ruta import trozos

NIVELES = ("TRAZA", "DEPURA", "INFO", "AVISO", "ERROR", "NADA")


def _texto(valor):
    try:
        return str(valor)
    except Exception:
        return f"<{type(valor).__name__} ilegible>"


def interpolar(plantilla, *valores):
    partes = _texto(plantilla).split("{}")
    salida = [partes[0]]
    for i, parte in enumerate(partes[1:]):
        salida.append(_texto(valores[i]) if i < len(valores) else "{}")
        salida.append(parte)
    return "".join(salida)


class _Sumidero:

    def __init__(self, destino, flujo):
        self.funcion = destino if callable(destino) else None
        self.flujo = flujo
        self.archivo = None
        if destino is not None and self.funcion is None:
            self.archivo = open(destino, "a", encoding="utf-8")
        self.candado = threading.Lock()

    def __call__(self, linea):
        if self.funcion is not None:
            self.funcion(linea)
            return
        salida = self.archivo or getattr(sys, self.flujo)
        with self.candado:
            salida.write(linea + "\n")
            salida.flush()

    def cerrar(self):
        if self.archivo is not None:
            self.archivo.close()


def _json(estado, cuerpo):
    return Respuesta(estado, [("Content-Type", "application/json")],
                     json.dumps(cuerpo, ensure_ascii=False).encode())


class Salud:

    def __init__(self, publico=False):
        self.publico = publico
        self.comprobaciones = []
        self.arranque = time.monotonic()

    def comprobacion(self, nombre, funcion):
        self.comprobaciones.append((nombre, funcion))

    def vivo(self, peticion):
        activo = round((time.monotonic() - self.arranque) * 1000)
        return _json(200, {"estado": "vivo", "activo_ms": activo})

    def listo(self, peticion):
        detalle = {}
        for nombre, funcion in self.comprobaciones:
            try:
                detalle[nombre] = "bien" if funcion() else "mal"
            except Exception as fallo:
                detalle[nombre] = f"mal: {type(fallo).__name__}: {_texto(fallo)}"
        listo = all(valor == "bien" for valor in detalle.values())
        cuerpo = {"estado": "listo" if listo else "no listo"}
        if not self.publico:
            cuerpo["comprobaciones"] = detalle
        return _json(200 if listo else 503, cuerpo)


class Registro:

    def __init__(self, destino=None, nivel="INFO"):
        if nivel not in NIVELES:
            raise ValueError(f"nivel desconocido: {nivel!r}; vale uno de {', '.join(NIVELES)}")
        self.minimo = NIVELES.index(nivel)
        self.sumidero = _Sumidero(destino, "stderr")

    def escribir(self, nivel, origen, plantilla, *valores, excepcion=None):
        if nivel not in NIVELES[:-1]:
            raise ValueError(f"nivel desconocido: {nivel!r}")
        if NIVELES.index(nivel) < self.minimo:
            return
        fecha = datetime.now(timezone.utc).isoformat(timespec="milliseconds")
        linea = f"{fecha} {nivel} {_texto(origen)} {interpolar(plantilla, *valores)}"
        if excepcion is not None:
            linea += f" | {type(excepcion).__name__}: {_texto(excepcion)}{_lugar(excepcion)}"
        self.sumidero(linea.replace("\r", "\\r").replace("\n", "\\n"))

    def cerrar(self):
        self.sumidero.cerrar()


def _lugar(excepcion):
    pila = traceback.extract_tb(getattr(excepcion, "__traceback__", None))
    if not pila:
        return ""
    return f" en {os.path.basename(pila[-1].filename)}:{pila[-1].lineno}"


SIN_RUTA = "(sin ruta)"
MUESTRAS = 1024
CUANTILES = ("0.5", "0.95", "0.99")
MESES = ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")


def _ignorada(prefijos, camino):
    partes = trozos(camino)
    return any(partes[:len(prefijo)] == prefijo for prefijo in prefijos)


def _prefijos(ignoradas):
    prefijos = [trozos(camino) for camino in ignoradas]
    if [] in prefijos:
        raise ValueError("ignorar la raíz ignoraría todas las rutas")
    return prefijos


class _Serie:

    def __init__(self):
        self.peticiones = 0
        self.errores = 0
        self.suma = 0.0
        self.recientes = deque(maxlen=MUESTRAS)


def _cuantil(ordenadas, q):
    return ordenadas[max(math.ceil(float(q) * len(ordenadas)) - 1, 0)]


def _etiqueta(valor):
    return valor.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def _numero(valor):
    return repr(round(valor, 3)) if isinstance(valor, float) else str(valor)


class Metricas:

    def __init__(self, ignoradas=()):
        self.ignoradas = _prefijos(ignoradas)
        self.series = {}
        self.candado = threading.Lock()
        self.arranque = time.monotonic()

    def anotar(self, patron, camino, estado, milisegundos):
        if _ignorada(self.ignoradas, camino):
            return
        with self.candado:
            serie = self.series.setdefault(patron or SIN_RUTA, _Serie())
            serie.peticiones += 1
            serie.errores += estado >= 400
            serie.suma += milisegundos
            serie.recientes.append(milisegundos)

    def exponer(self, peticion):
        with self.candado:
            series = {ruta: (s.peticiones, s.errores, s.suma, sorted(s.recientes))
                      for ruta, s in sorted(self.series.items())}
        lineas = []

        def familia(nombre, tipo, ayuda):
            lineas.extend([f"# HELP {nombre} {ayuda}", f"# TYPE {nombre} {tipo}"])

        familia("cero_requests_total", "counter", "Peticiones atendidas, por patrón de ruta")
        for ruta, (peticiones, _, _, _) in series.items():
            lineas.append(f'cero_requests_total{{ruta="{_etiqueta(ruta)}"}} {peticiones}')
        familia("cero_request_errors_total", "counter", "Respuestas 4xx o 5xx, por patrón de ruta")
        for ruta, (_, errores, _, _) in series.items():
            lineas.append(f'cero_request_errors_total{{ruta="{_etiqueta(ruta)}"}} {errores}')
        familia("cero_all_requests_total", "counter", "Peticiones atendidas en total")
        lineas.append(f"cero_all_requests_total {sum(s[0] for s in series.values())}")
        familia("cero_all_request_errors_total", "counter", "Respuestas 4xx o 5xx en total")
        lineas.append(f"cero_all_request_errors_total {sum(s[1] for s in series.values())}")
        familia("cero_request_duration_ms", "summary",
                f"Latencia en milisegundos; cuantiles de las últimas {MUESTRAS} peticiones")
        for ruta, (peticiones, _, suma, ordenadas) in series.items():
            ruta = _etiqueta(ruta)
            for q in CUANTILES:
                valor = _numero(_cuantil(ordenadas, q))
                lineas.append(f'cero_request_duration_ms{{ruta="{ruta}",quantile="{q}"}} {valor}')
            lineas.append(f'cero_request_duration_ms_sum{{ruta="{ruta}"}} {_numero(suma)}')
            lineas.append(f'cero_request_duration_ms_count{{ruta="{ruta}"}} {peticiones}')
        familia("cero_uptime_ms", "gauge", "Milisegundos desde el arranque")
        lineas.append(f"cero_uptime_ms {round((time.monotonic() - self.arranque) * 1000)}")
        return Respuesta(200, [("Content-Type", "text/plain; version=0.0.4; charset=utf-8")],
                         ("\n".join(lineas) + "\n").encode())


def _clf(valor, espacio=False):
    salida = []
    for octeto in valor.encode("utf-8", "backslashreplace"):
        caracter = chr(octeto)
        if caracter in '"\\':
            salida.append("\\" + caracter)
        elif octeto < 0x21 and not (espacio and octeto == 0x20) or octeto > 0x7e:
            salida.append(f"\\x{octeto:02x}")
        else:
            salida.append(caracter)
    return "".join(salida)


def _fecha_clf():
    ahora = datetime.now(timezone.utc)
    return f"{ahora:%d}/{MESES[ahora.month - 1]}/{ahora:%Y:%H:%M:%S} +0000"


class Acceso:

    def __init__(self, destino=None, ignoradas=(), personales=True):
        self.ignoradas = _prefijos(ignoradas)
        self.personales = personales
        self.sumidero = _Sumidero(destino, "stdout")

    def anotar(self, peticion, estado, enviados):
        if _ignorada(self.ignoradas, peticion.camino):
            return
        destino = peticion.destino if self.personales else peticion.camino
        usuario = _texto(peticion.usuario) if self.personales and peticion.usuario else ""
        linea = _clf(f"{peticion.verbo} {destino} {peticion.version}", True)
        self.sumidero(f"{_clf(peticion.cliente) or '-'} - {_clf(usuario) or '-'} [{_fecha_clf()}] "
                      f"\"{linea}\" {estado} {enviados or '-'}")

    def cerrar(self):
        self.sumidero.cerrar()
