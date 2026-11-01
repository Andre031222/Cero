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
| `/grande` | GET | responder 1 MiB, que es lo que usa `H2-054` |

Esto se aprendió fallando: el primer servidor de prueba contestaba `ok` a todo sin tocar el
cuerpo, y el vector del trozo no hexadecimal daba 200 en vez de 400. La implementación estaba
bien; el arnés no ejercitaba el camino. Es la misma lección que el banco de rendimiento dio esa
misma semana, en otra capa.

## Los vectores

23, sobre 13 apartados de RFC 9112 y RFC 9110, extraídos de la batería de Java a
`conformidad-http1.json`. Cada uno lleva el apartado que lo exige, los octetos que se mandan y el
estado que se espera.

## Los ataques: `ataques.py`

Siete vectores más, para los requisitos que solo se ven con el reloj en la mano: los cinco de
HTTP/2 Bomb (`H2-050` a `H2-054`) y los dos de plazos de HTTP/1.1 (`HTTP-024`, `HTTP-025`). Este
no se conecta a un servidor que ya corre: **lo arranca él**, porque necesita plazos cortos y porque
`HTTP-025` comprueba justo que el servidor se niegue a arrancar.

```bash
python3 spec/banco/ataques.py ./c++/build/conforme
python3 spec/banco/ataques.py ./rust/target/release/conforme
python3 spec/banco/ataques.py java -cp java/cero-http/target/classes:java/cero-http/target/test-classes cero.http.Conforme
```

A la orden le añade `<puerto> <plazo en ms>`. El servidor de conformidad tiene que usar ese plazo
para esperar crédito y para escribir, y el doble para la inactividad; con 0 o con un número
negativo tiene que terminar con un código distinto de cero sin llegar a escuchar. El banco usa un
segundo, y tarda unos cinco con C++ o Rust; algo más con Java, por el arranque de la JVM.

| Vector | El ataque | Lo que se espera |
|---|---|---|
| `H2-050` | una `cookie` en la tabla dinámica y 299 referencias de un octeto: 303 campos en 314 octetos | GOAWAY ENHANCE_YOUR_CALM |
| `H2-051` | una cabecera de 4 000 octetos referenciada hasta justo el tope anunciado, y una vez más | la primera se atiende; la segunda, GOAWAY ENHANCE_YOUR_CALM |
| `H2-052` | ventana inicial 0 y un `WINDOW_UPDATE` de un octeto cada cuarto de plazo | RST_STREAM CANCEL antes de que salga la respuesta entera |
| `H2-053` | ningún flujo y un `PING` cada medio plazo | GOAWAY NO_ERROR al pasar la inactividad |
| `H2-054` | ventana inicial 0 y 40 peticiones a `/grande` | GOAWAY ENHANCE_YOUR_CALM |
| `HTTP-024` | una petición keep-alive y después silencio | la conexión se cierra |
| `HTTP-025` | arrancar con un plazo de 0 y de -1 | el proceso termina con error y nadie escucha |
