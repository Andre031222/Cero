# ejemplo

Una aplicación pequeña de punta a punta: tareas con formularios, CSRF, validación, base de datos y
una API REST paginada. Es la prueba de que los módulos sirven juntos, no solo por separado.

No se publica en Maven Central: nadie la va a declarar como dependencia.

## Las clases

- **`App`** monta la aplicación. `App.app(jdbc)` la devuelve **sin arrancar**, para que las pruebas
  la levanten en un puerto libre con `TestServer`.
- **`Tarea`** y **`Tareas`**: el registro y su repositorio.
- **`TareaController`**: los formularios. **`ApiController`**: la API.
