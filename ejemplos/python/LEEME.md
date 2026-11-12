# Ejemplo en Python

Tres rutas, sin dependencias y sin venv para Cero (Python 3.12 o posterior):

| Ruta | Respuesta |
|---|---|
| `GET /` | `{"hola":"mundo"}` |
| `GET /saludo/{nombre}` | `{"saludo":"hola, <nombre>"}` |
| `POST /eco` | el JSON recibido |

Escucha en `127.0.0.1`, en el puerto 8080 o en el de la variable `PUERTO`.

## Empezar en Python

**Con pip, desde el repositorio:**

```bash
pip install "git+https://github.com/Andre031222/Cero#subdirectory=python"
python3 app.py
```

**Con un solo archivo, sin pip.** Con el repositorio clonado, desde `python/`:

```bash
python3 -m cero.empaquetar ../ejemplos/python app.pyz app:main
python3 app.pyz
```

Cero no tiene dependencias, así que ninguno de los dos caminos pide un venv. Probar:

```bash
curl 127.0.0.1:8080/
curl 127.0.0.1:8080/saludo/ana
curl -d '{"a":1}' 127.0.0.1:8080/eco
```

## Prueba de punta a punta

```bash
python3 prueba_punta_a_punta.py
```

Arranca `app.py` en un puerto libre de 127.0.0.1, comprueba las tres rutas, y repite la prueba con el
ejemplo empaquetado en un `.pyz` y arrancado con `python3 -I`.

## Memoria en reposo

**24032 KiB (unos 23,5 MiB)** de RSS, con el servidor recién arrancado y sin peticiones, en
CPython 3.12 sobre macOS (arm64), con Cero instalado por pip en un venv de prueba:

```bash
PUERTO=18431 python3 app.py & sleep 1.5; ps -o rss= -p $!
```

Es una sola medición en una sola máquina.
