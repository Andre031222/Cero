# HTTP/2 — qué hay y qué falta

Segundo bloque del contrato, y el único que se escribe con la implementación a medio hacer. Va
así a propósito: en `produccion.md` HTTP/2 llevaba tres versiones como hueco declarado, y pasar de
«no está» a «está a medias, y aquí está la lista exacta» es la diferencia entre una promesa y un
trabajo con estado.

**Solo h2c**, HTTP/2 en claro. Sobre TLS todavía no.

## Lo que está, y probado

| Pieza | Estado | Cómo se comprueba |
|---|---|---|
| HPACK — tabla estática, dinámica y Huffman | completo | los 32 vectores del apéndice C del RFC 7541 |
| Capa de tramas | completo para los tipos que usa un servidor | `Http2Tests` |
| Flujos concurrentes | uno por hilo virtual | pendiente de prueba concurrente |
| Control de flujo, conexión y flujo | completo | 200 KB de bajada y 533 KB de eco con `curl` |
| SETTINGS negociados | completo | ventana inicial y tamaño de trama |
| PING, RST_STREAM, GOAWAY | completo | `Http2Tests` |
| CONTINUATION | completo | bloques de cabeceras que no caben en una trama |
| Entrada por conocimiento previo | completo | `curl --http2-prior-knowledge` |

Las tablas de HPACK **no están escritas a mano**: se generan del RFC y se validan con la suma de
Kraft, que en un código prefijo completo vale exactamente 1. Una tabla de 257 filas copiada a mano
no revienta cuando se equivoca — decodifica mal.

## Lo que falta, por orden de lo que más pesa

1. **`Upgrade: h2c`.** Es la puerta que usa `curl --http2` a secas y cualquier cliente que no sepa
   de antemano que el servidor habla h2. Sin ella, solo entra quien ya lo sabe. `Http2` ya acepta
   una petición inicial como flujo 1; falta el 101 y decodificar `HTTP2-Settings`.

2. **h2 sobre TLS con ALPN.** Sin esto, ningún navegador usará HTTP/2 con Cero: los navegadores no
   hablan h2c. Es la pieza que convierte esto en algo que sirve de cara a internet, y hasta que
   esté, el despliegue recomendado sigue llevando un proxy delante.

3. **Multiplexación bajo carga.** Un flujo por hilo virtual está escrito y funciona con peticiones
   sueltas, pero no hay prueba de decenas de flujos a la vez sobre una conexión — que es la razón
   de ser del protocolo.

4. **Entradas hostiles.** Tramas cortadas, longitudes que mienten, flujos con identificador hacia
   atrás, CONTINUATION suelto, bombas de expansión en HPACK. HTTP/1.1 tiene 23 vectores de
   conformidad; h2 todavía no tiene ninguno.

5. **Respuestas por `stream()`.** Se acumulan en memoria en vez de ir saliendo, porque el control
   de flujo obliga a esperar ventana antes de cada trama. Para respuestas grandes, HTTP/1.1 sigue
   siendo el camino.

6. **Trailers.** Se leen y se descartan.

## Lo que no va a estar, y por qué

- **PUSH_PROMISE.** Está en desuso y los navegadores lo retiraron. Se anuncia deshabilitado en
  SETTINGS, que es lo que manda el RFC.
- **PRIORITY.** El RFC 9113 deprecó el esquema de prioridades del 7540. Se lee y se descarta.
- **WebSocket sobre h2** (RFC 8441, el CONNECT extendido). `switchProtocols()` falla con un 501
  explícito en vez de devolver un 101 que en h2 no significa nada.

## Requisitos numerados

Empiezan en `H2-001` y continúan la numeración de [conformidad.md](conformidad.md), que cubre
HTTP/1.1. Se escribirán a medida que existan las pruebas que los verifican: la regla del contrato
—no se especifica lo que no está probado— vale igual para este bloque.
