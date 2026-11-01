import re
import secrets
import threading
import time

COOKIE = "cero_sid"
INACTIVIDAD = 30 * 60
IDENTIFICADOR = re.compile(r"[0-9a-f]{64}")
QUITADA = object()


class SesionInvalidada(Exception):
    pass


def identificador():
    return secrets.token_hex(32)


def cabecera_cookie(id, segura):
    return f"{COOKIE}={id}; Path=/; HttpOnly; SameSite=Lax" + ("; Secure" if segura else "")


def id_de_cookie(valores):
    for valor in valores:
        for par in valor.split(";"):
            nombre, _, id = par.strip().partition("=")
            if nombre == COOKIE:
                return id if IDENTIFICADOR.fullmatch(id) else None
    return None


def fundir(atributos, cambios):
    for clave, valor in cambios.items():
        if valor is QUITADA:
            atributos.pop(clave, None)
        else:
            atributos[clave] = valor


class Sesion:

    def __init__(self, almacen, id, atributos, nueva=False):
        self.almacen = almacen
        self.id = id
        self.viva = True
        self.atributos = atributos
        self.cambios = {}
        self.pendiente = nueva
        self.tocar = False

    def _comprobar(self):
        if not self.viva:
            raise SesionInvalidada("la sesión está invalidada")

    def leer(self, clave, defecto=None):
        self._comprobar()
        return self.atributos.get(clave, defecto)

    def poner(self, clave, valor):
        self._comprobar()
        if not isinstance(clave, str) or not isinstance(valor, str):
            raise TypeError("la clave y el valor de un atributo de sesión son cadenas")
        self.atributos[clave] = valor
        self.cambios[clave] = valor

    def quitar(self, clave):
        self._comprobar()
        self.atributos.pop(clave, None)
        self.cambios[clave] = QUITADA

    def invalidar(self):
        if self.viva:
            self.viva = False
            self.atributos, self.cambios, self.pendiente = {}, {}, False
            self.almacen.borrar(self.id)

    def rotar(self):
        self._comprobar()
        nuevo = self.almacen.renombrar(self.id)
        self.id, self.pendiente = nuevo, True
        return nuevo

    def cookie_pendiente(self):
        pendiente, self.pendiente = self.pendiente, False
        return self.id if pendiente else None


class Memoria:

    def __init__(self, inactividad=INACTIVIDAD):
        self.inactividad = inactividad
        self.filas = {}
        self.candado = threading.Lock()
        self.barrido = time.time()

    def _nuevo_id(self):
        id = identificador()
        while id in self.filas:
            id = identificador()
        return id

    def crear(self):
        ahora = time.time()
        with self.candado:
            if ahora - self.barrido > self.inactividad:
                self.filas = {k: f for k, f in self.filas.items()
                              if ahora - f[1] <= self.inactividad}
                self.barrido = ahora
            id = self._nuevo_id()
            self.filas[id] = [{}, ahora]
        return Sesion(self, id, {}, nueva=True)

    def recuperar(self, id):
        ahora = time.time()
        with self.candado:
            fila = self.filas.get(id)
            if fila is None:
                return None
            if ahora - fila[1] > self.inactividad:
                del self.filas[id]
                return None
            fila[1] = ahora
            return Sesion(self, id, dict(fila[0]))

    def guardar(self, sesion):
        with self.candado:
            fila = self.filas.get(sesion.id)
            if fila is not None and sesion.viva:
                fundir(fila[0], sesion.cambios)
        sesion.cambios = {}

    def borrar(self, id):
        with self.candado:
            self.filas.pop(id, None)

    def renombrar(self, viejo):
        with self.candado:
            if viejo not in self.filas:
                raise SesionInvalidada("la sesión ya no está en el almacén")
            nuevo = self._nuevo_id()
            self.filas[nuevo] = self.filas.pop(viejo)
        return nuevo
