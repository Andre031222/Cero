# Empezar en Java

La misma API pequeña que en los otros lenguajes. Hace falta Java 25 o posterior y Maven; Cero no
trae nada más consigo.

| Petición | Respuesta |
|---|---|
| `GET /` | `{"hola":"mundo"}` |
| `GET /saludo/{nombre}` | `{"saludo":"hola, <nombre>"}` |
| `POST /eco` | el JSON recibido; si no es JSON, 400 |

Escucha en el puerto 8080 o en el de `PUERTO`, y solo en `127.0.0.1`, la propia máquina. Para
atender en todas las interfaces —otra máquina, un contenedor—, cambiar `.host("127.0.0.1")` por
`.host("0.0.0.0")`.

## Instalar

Hay dos caminos, y conviene saber cuál se está usando.

**Desde Maven Central.** Es la versión publicada, `0.8.0`. En el `pom.xml`:

```xml
<dependency>
    <groupId>dev.ginit.cero</groupId>
    <artifactId>cero-core</artifactId>
    <version>0.8.0</version>
</dependency>
```

**Desde el código de este repositorio.** Con el repositorio clonado:

```bash
cd java && mvn -q install -DskipTests
```

Eso compila la versión actual y la deja en `~/.m2`. Ojo: el `pom.xml` del repositorio todavía dice
`0.8.0`, así que esa copia local tapa a la de Maven Central con el mismo número. No es la misma
cosa: es el código de `main`, con lo que haya cambiado desde que se publicó la `0.8.0`. Para volver
a la publicada, borrar `~/.m2/repository/dev/ginit/cero` y compilar otra vez.

El ejemplo compila y pasa su prueba con las dos.

## El ejemplo

[`src/main/java/App.java`](src/main/java/App.java) entero. Es un archivo fuente compacto de Java
25: sin clase, sin paquete, y con `java.util` ya importado.

```java
import cero.core.Cero;
import cero.core.Json;
import cero.core.Result;

void main() throws InterruptedException {
    Cero.app()
            .host("127.0.0.1")
            .port(Integer.parseInt(System.getenv().getOrDefault("PUERTO", "8080")))
            .routes(r -> r
                    .get("/", c -> Map.of("hola", "mundo"))
                    .get("/saludo/{nombre}", c -> Map.of("saludo", "hola, " + c.pathVariable("nombre")))
                    .post("/eco", c -> eco(c.bodyText())))
            .start()
            .await();
}

Result eco(String cuerpo) {
    try {
        return Result.json(Json.read(cuerpo));
    } catch (IllegalArgumentException noEsJson) {
        return Result.status(400, noEsJson.getMessage());
    }
}
```

Una acción que devuelve un `Map` sale como JSON. `Json.read` rechaza un cuerpo que no es JSON con
`IllegalArgumentException`, y Cero convierte en 500 cualquier excepción que no sea `HttpException`;
por eso `eco` la atrapa y contesta 400 él mismo.

Las rutas ya caben en una línea con `routes(r -> r.get(…))`, así que no hizo falta añadir una forma
corta a la API. Los controladores con anotaciones (`@Route`, `@Get`) siguen ahí para cuando la
aplicación crezca.

## Arrancarlo

```bash
mvn -q package
java -jar target/ejemplo.jar
curl 127.0.0.1:8080/saludo/ana
curl -d '{"a":1}' 127.0.0.1:8080/eco
```

En PowerShell:

```powershell
mvn -q package
$env:PUERTO = 9000; java -jar target/ejemplo.jar
Invoke-RestMethod 127.0.0.1:9000/eco -Method Post -Body '{"a":1}'
```

`package` deja `cero-core` y `cero-http` en `target/lib`, al lado del jar, y el manifiesto los
nombra; por eso basta `java -jar`.

## Prueba de punta a punta

```bash
java pruebas/PuntaAPunta.java
```

Arranca `target/ejemplo.jar` en un puerto libre de 127.0.0.1 y comprueba las tres rutas, el 400 de
`/eco` y el eco de un JSON que no es un objeto. Solo usa el JDK.

## Memoria

En reposo, recién arrancado y sin ninguna petición, medido tres veces seguidas el 2026-11-11 en
macOS 26.6 sobre arm64 (32 GB), con OpenJDK 25.0.5:

| Opciones de la JVM | Memoria residente |
|---|---|
| ninguna | 58 464, 59 520 y 58 608 KiB, unos **57 MiB** |
| `-Xmx64m -XX:+UseSerialGC`, lo que propone [`docs/produccion.md`](../../docs/produccion.md) cuando manda la memoria | 57 360, 56 624 y 57 312 KiB, unos **56 MiB** |

```bash
PUERTO=18600 java -jar target/ejemplo.jar >/dev/null & P=$!; sleep 1; ps -o rss= -p $P; kill $P
PUERTO=18600 java -Xmx64m -XX:+UseSerialGC -jar target/ejemplo.jar >/dev/null & P=$!; sleep 1; ps -o rss= -p $P; kill $P
```

En reposo casi no hay diferencia: lo que ocupa es la propia JVM, y el montón apenas se ha tocado.
Las opciones pesan con carga, que es donde `docs/produccion.md` las mide. Son mediciones en una sola
máquina.
