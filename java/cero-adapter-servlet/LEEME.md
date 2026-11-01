# cero-adapter-servlet

La puerta de salida: la misma aplicación Cero se despliega dentro de un contenedor de servlets
(Tomcat 10.1 o posterior) sin tocar su código, para que adoptar Cero sea reversible. Es el único
módulo que referencia `jakarta.*`, y en *scope* `provided`.

El código no lleva comentarios. Lo que explicaban y el código no dice solo está aquí.

## Las clases

- **`CeroServlet`** recibe una aplicación `Cero` o un `Handler` y le pasa cada petición envuelta.
  Un fallo que no sea de red, si la respuesta aún no salió, se contesta con un 500 sin detalles.
- **`RequestOverServlet` y `ResponseOverServlet`** presentan la petición y la respuesta del
  contenedor como las de `cero-http`.
- **`SessionOverServlet`** usa la sesión del contenedor. `regenerateId()` llama a
  `changeSessionId()`, que cambia el identificador, conserva los atributos y emite la cookie nueva:
  lo mismo que hace `Sessions` con el servidor de Cero.

## Límites

**`switchProtocols()` no está.** El socket lo tiene el contenedor, así que WebSocket y lo que ceda
la conexión solo funcionan sobre el servidor propio de Cero.
