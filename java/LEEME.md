# Cero en Java

Siete módulos Maven: `cero-http`, `cero-core`, `cero-data`, `cero-test`, `cero-adapter-servlet`,
`cero-launcher` y la aplicación `ejemplo`. Cada uno tiene su `LEEME.md`. Se construye y se prueba
con `mvn -q clean install` desde esta carpeta.

## Estilo

- **`void main()` de instancia** (JEP 512, Java 25) en los programas que lanza `java`: las suites
  de pruebas, `ejemplo.App`, `Nuevo`, `Migraciones` y el `App` que genera `cero new`. Solo
  `Packager` conserva `public static void main`, porque su único constructor recibe la clase
  principal y un `main` de instancia obligaría a añadir otro vacío.
- **`var`** cuando el tipo se lee en la misma línea: un `new` o un *cast*. Si a la derecha hay una
  llamada cuyo tipo no se ve, el tipo se escribe.
- **`import`** en lugar de nombres calificados. Queda uno, donde el nombre choca: `java.sql.Date`
  junto a `java.util.Date` en `Json`. `RequestOverServlet` recorre las partes del servlet con
  `var` para no chocar con `cero.http.Part`.
- **Tablas en lugar de cadenas de `if` por tipo:** las conversiones de `Json`, los tipos de
  OpenAPI y lo que `Binder` inyecta sin anotación.
- **Sin comentarios.** La explicación vive en estos `LEEME.md`; `herramientas/comentarios.py`
  vigila que no vuelvan.
- **Sin dependencias** fuera del JDK en los cuatro módulos del núcleo.
