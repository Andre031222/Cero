# cero-launcher

Lo que hay detrás de la orden `cero`: el generador de proyectos, el empaquetado en un jar
ejecutable y la verificación de migraciones.

El código no lleva comentarios. Lo que explicaban y el código no dice solo está aquí.

## Las clases

- **`Nuevo`** es `cero new`: arma un proyecto y lo escribe en disco. `cero new tienda h2` se
  entiende como nombre y motor; sin ese caso, `h2` acabaría siendo el `groupId`.
- **`GeneradorProyecto`** arma el proyecto como un ZIP en memoria. Emite un jar que arranca solo,
  no un WAR con `web.xml`: el proyecto no necesita contenedor. Limpia lo que llega de la línea de
  órdenes: el nombre visible admite letras, números, espacio y `. _ -` hasta 60 caracteres, y el
  paquete tiene que ser un nombre Java válido.
- **`Frontend`** es la carpeta `frontend/` de un proyecto generado con `--front`. Deja el backend
  listo para hablarle: CORS para los puertos de desarrollo habituales y la carpeta desde la que se
  servirá lo compilado.
- **`Packager`** junta la aplicación y sus jar en uno solo con `java.util.jar`, sin plugins de
  terceros. Las firmas de los jar de entrada se descartan, porque al mezclar contenidos dejan de
  valer y la JVM rechazaría el resultado: si una dependencia necesita conservar su firma, este
  empaquetado no sirve.
- **`Migraciones`** es `cero migraciones --verificar [directorio] [url-jdbc]`: aplica el lote
  entero sobre una base vacía y desechable y cuenta qué pasó, sin tocar la de verdad.
- **`Idioma`** tiene los textos de la orden en castellano e inglés. Elige igual que el guion `cero`
  y los instaladores —manda `CERO_LANG`, y si no el entorno—, para que las tres piezas hablen igual
  en la misma terminal. Sin traducción cae al castellano: un hueco tiene que verse raro, no en
  blanco.
- **`Version`** lee la versión del pom por el filtrado de recursos. Es lo único que se filtra
  (`src/main/filtrado`), y existe porque un número escrito a mano en dos sitios acaba siendo dos
  números distintos. `cero new` la escribe en cada pom generado.

## `migrar.py`

`src/main/resources/migrar.py` lleva una aplicación de LuxCore o Corvo a Cero. Es de transición y
se retira en la 1.0; qué cambia y qué hay que hacer a mano está en
[`docs/migrar-a-cero.md`](../../docs/migrar-a-cero.md). No hay capa de compatibilidad en el
framework a propósito: sería código muerto para siempre.
