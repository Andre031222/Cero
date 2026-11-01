import json
import re
import secrets
import sqlite3
import threading
import time
from contextlib import closing

COOKIE = "cero_sid"
INACTIVIDAD = 30 * 60
IDENTIFICADOR = re.compile(r"[0-9a-f]{64}")
NOMBRE_TABLA = re.compile(r"(?!(?i:sqlite_))[A-Za-z_][A-Za-z0-9_]{0,62}")
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


class Tabla:

    def __init__(self, ruta, tabla, inactividad=INACTIVIDAD):
        if not NOMBRE_TABLA.fullmatch(tabla):
            raise ValueError(f"nombre de tabla no admitido: {tabla!r}")
        self.ruta = ruta
        self.tabla = tabla
        self.inactividad = inactividad
        with self._conexion() as c:
            c.execute(f'CREATE TABLE IF NOT EXISTS "{tabla}" (id TEXT PRIMARY KEY, '
                      "atributos TEXT NOT NULL, tocada REAL NOT NULL)")

    def _conexion(self):
        return closing(sqlite3.connect(self.ruta, timeout=10, isolation_level=None))

    def crear(self):
        with self._conexion() as c:
            while True:
                id = identificador()
                try:
                    c.execute(f'INSERT INTO "{self.tabla}" VALUES (?, ?, ?)',
                              (id, "{}", time.time()))
                    return Sesion(self, id, {}, nueva=True)
                except sqlite3.IntegrityError:
                    continue

    def recuperar(self, id):
        if id is None:
            return None
        ahora = time.time()
        with self._conexion() as c:
            fila = c.execute(f'SELECT atributos, tocada FROM "{self.tabla}" WHERE id = ?',
                             (id,)).fetchone()
            if fila is None:
                return None
            if ahora - fila[1] > self.inactividad:
                c.execute(f'DELETE FROM "{self.tabla}" WHERE id = ?', (id,))
                return None
        sesion = Sesion(self, id, json.loads(fila[0]))
        sesion.tocar = ahora - fila[1] > self.inactividad / 2
        return sesion

    def guardar(self, sesion):
        if not sesion.viva or not (sesion.cambios or sesion.tocar):
            return
        with self._conexion() as c:
            c.execute("BEGIN IMMEDIATE")
            fila = c.execute(f'SELECT atributos FROM "{self.tabla}" WHERE id = ?',
                             (sesion.id,)).fetchone()
            if fila is not None:
                atributos = json.loads(fila[0])
                fundir(atributos, sesion.cambios)
                c.execute(f'UPDATE "{self.tabla}" SET atributos = ?, tocada = ? WHERE id = ?',
                          (json.dumps(atributos), time.time(), sesion.id))
            c.execute("COMMIT")
        sesion.cambios, sesion.tocar = {}, False

    def borrar(self, id):
        with self._conexion() as c:
            c.execute(f'DELETE FROM "{self.tabla}" WHERE id = ?', (id,))

    def renombrar(self, viejo):
        with self._conexion() as c:
            while True:
                nuevo = identificador()
                try:
                    cambiadas = c.execute(f'UPDATE "{self.tabla}" SET id = ? WHERE id = ?',
                                          (nuevo, viejo)).rowcount
                except sqlite3.IntegrityError:
                    continue
                if cambiadas == 0:
                    raise SesionInvalidada("la sesión ya no está en el almacén")
                return nuevo
