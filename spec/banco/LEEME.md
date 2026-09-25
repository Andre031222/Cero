# Banco de conformidad

Los vectores son **bytes sobre un socket**. No saben en qué lenguaje está escrito quien responde,
y por eso el mismo juez vale para `java/`, para `rust/` y para las implementaciones que vengan.
Es lo que convierte `spec/` en un contrato comprobable en vez de un documento.

```bash
python3 spec/banco/correr.py 127.0.0.1 8777
```

## Lo que el servidor bajo prueba tiene que ofrecer

No basta con que escuche. Seis vectores esperan 200 y varios ejercitan el encuadre del cuerpo, y
**el encuadre solo se comprueba si alguien lee el cuerpo**. Un servidor que responde sin leerlo
pasa por válido sin haber parseado nada.

| Ruta | Verbo | Qué tiene que hacer |
|---|---|---|
| `/` | GET | responder 200 |
| `/eco` | GET, POST | **leer el cuerpo entero** y devolverlo |

Esto se aprendió fallando: el primer servidor de prueba contestaba `ok` a todo sin tocar el
cuerpo, y el vector del trozo no hexadecimal daba 200 en vez de 400. La implementación estaba
bien; el arnés no ejercitaba el camino. Es la misma lección que el banco de rendimiento dio esa
misma semana, en otra capa.

## Los vectores

23, sobre 13 apartados de RFC 9112 y RFC 9110, extraídos de la batería de Java a
`conformidad-http1.json`. Cada uno lleva el apartado que lo exige, los octetos que se mandan y el
estado que se espera.
