# cero-test

Pruebas para una aplicación Cero sin cablear nada: levanta la aplicación en un puerto libre,
le pide algo con un cliente que conserva las cookies, y afirma sobre la respuesta.

Sin dependencias fuera del JDK.

## Añadirlo

```xml
<dependency>
    <groupId>dev.ginit.cero</groupId>
    <artifactId>cero-test</artifactId>
    <version>0.8.0</version>
    <scope>test</scope>
</dependency>
```

## Un ejemplo completo

```java
import cero.test.TestClient;
import cero.test.TestServer;
import java.util.Map;

public class AccesoTest {

    public static void main(String[] args) {
        try (TestServer app = TestServer.start(MiAplicacion.app())) {
            TestClient client = app.client();

            client.get("/yo").status(401);
            client.form("/acceso", Map.of("usuario", "ada", "clave", "secreta"))
                  .status(302)
                  .cookie("CEROSESSION");
            client.get("/yo").ok().json("usuario.nombre", "Ada");
        }
    }
}
```

El cliente guarda las cookies, así que la tercera petición sigue dentro de la sesión que abrió
la segunda. `close()` cierra los clientes y para el servidor.

## Las tres clases

### `TestServer`

```java
TestServer.start(Cero app)              // la aplicación, en 127.0.0.1 y en un puerto libre
TestServer.start(Class<?>... controllers)
int port()          String url()        String url(String path)
TestClient client()                     // cliente nuevo, tarro de cookies propio
Server server()     void close()
```

El puerto lo elige el sistema operativo: dos módulos que prueban en paralelo no chocan.

### `TestClient`

```java
TestClient.of(String baseUrl)
TestClient http1()   TestClient http2()          // por omisión, negocia
TestClient header(String name, String value)     // para todas las peticiones siguientes

TestResponse get(String path)
TestResponse delete(String path)
TestResponse post(String path)
TestResponse post(String path, Object json)      // una cadena va tal cual; lo demás se serializa
TestResponse put(String path, Object json)
TestResponse patch(String path, Object json)
TestResponse form(String path, Map<String, String> fields)
TestResponse send(String method, String path, String contentType, String body)

String cookie(String name)   void close()
```

No sigue redirecciones: un acceso correcto responde 302 y esa respuesta es la que se mira.

### `TestResponse`

Los lectores devuelven el valor; los demás afirman y se encadenan.

```java
int status()   String body()   String header(String name)
HttpClient.Version version()   HttpResponse<String> raw()
Object json()                  Object json(String path)     // "usuario.nombre", "items.0.id"

TestResponse ok()                                  // 200
TestResponse status(int expected)
TestResponse header(String name, String expected)
TestResponse contentType(String expected)          // compara sin "; charset=..."
TestResponse contains(String fragment)
TestResponse json(String path, Object expected)
TestResponse cookie(String name)
```

Un fallo dice qué se esperaba, qué llegó y un extracto del cuerpo:

```
GET /json: se esperaba json usuario.nombre = <Grace> y llegó <Ada>
  estado: 200
  cuerpo: {"usuario":{"nombre":"Ada","edad":36}}
```

## Base de datos

`TestDatabase` necesita `cero-data` y el driver JDBC en el classpath de pruebas; en `cero-test`
los dos son opcionales.

```java
try (TestDatabase db = TestDatabase.of("jdbc:h2:mem:pruebas")) {
    db.migrate(Migrations.from(Path.of("db/migraciones")));
    // la aplicación bajo prueba ya usa esta base: queda registrada como origen por omisión
}
```

`migrate` aplica el lote entero contra la base vacía y falla nombrando la migración rota.

## Cómo se lanzan

Cero no usa JUnit: cada módulo tiene una clase con `main` que corre sus pruebas y sale con
código distinto de cero si alguna falla, y el `exec-maven-plugin` la lanza en la fase `test`.
Se copia del `pom.xml` de este módulo cambiando el nombre de la clase. Si tu proyecto sí usa
JUnit, `TestServer` y `TestDatabase` son `AutoCloseable` y encajan en un `@BeforeAll`/`@AfterAll`
o en un `try`-con-recursos sin nada más.
