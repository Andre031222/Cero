# Apps de referencia para el benchmark

Cada framework expone **exactamente** los mismos dos endpoints:

- `GET /plaintext` → `text/plain`, cuerpo `OK`
- `GET /json` → `application/json`, cuerpo `{"message":"hello","n":42}`

Versiones fijadas (2026-07). Java 17 para todos, modo JVM (indicar aparte si se corre nativo).
Puerto interno `8080` salvo que se note.

---

## JxMVC 3.4.0

`BenchController` sobre `jxmvc-core:3.4.0` (dependencia única, `jakarta.jakartaee-api` provided),
desplegado como WAR en Tomcat 10.1 (mismo container para todos los WAR).

```java
package jxmvc.bench;
import jxmvc.core.*;
import jxmvc.core.JxMapping.*;

@JxControllerMain
@JxControllerMapping("")
public class BenchController extends JxController {
    @JxGetMapping("plaintext")
    public ActionResult plaintext() { return text("OK"); }

    @JxGetMapping("json")
    public ActionResult json() { return json("{\"message\":\"hello\",\"n\":42}"); }
}
```
Build/run: `mvn -q clean package && mvn cargo:run` (Tomcat 10.1, puerto 8080).
Tamaño desplegable = `jxmvc-bench.war` (core + clases) **+** el Tomcat que lo sirve.

---

## Spring Boot 3.3 (spring-boot-starter-web, Tomcat embebido)

```java
@org.springframework.boot.autoconfigure.SpringBootApplication
@org.springframework.web.bind.annotation.RestController
public class App {
  public static void main(String[] a){ org.springframework.boot.SpringApplication.run(App.class,a); }
  @org.springframework.web.bind.annotation.GetMapping(value="/plaintext",produces="text/plain")
  public String plaintext(){ return "OK"; }
  @org.springframework.web.bind.annotation.GetMapping(value="/json",produces="application/json")
  public String json(){ return "{\"message\":\"hello\",\"n\":42}"; }
}
```
`pom.xml`: parent `spring-boot-starter-parent:3.3.x`, dep `spring-boot-starter-web`, plugin
`spring-boot-maven-plugin`. Build/run: `mvn -q clean package && java -jar target/app.jar`.
Tamaño desplegable = uber-JAR (incluye Tomcat embebido).

---

## Quarkus 3.11 (RESTEasy Reactive, JVM)

```java
@jakarta.ws.rs.Path("")
public class BenchResource {
  @jakarta.ws.rs.GET @jakarta.ws.rs.Path("/plaintext")
  @jakarta.ws.rs.Produces("text/plain") public String plaintext(){ return "OK"; }
  @jakarta.ws.rs.GET @jakarta.ws.rs.Path("/json")
  @jakarta.ws.rs.Produces("application/json") public String json(){ return "{\"message\":\"hello\",\"n\":42}"; }
}
```
Deps: `quarkus-rest` (o `quarkus-resteasy-reactive`). Build/run JVM:
`mvn -q clean package && java -jar target/quarkus-app/quarkus-run.jar`.
(Nativo: `mvn package -Dnative` — reportar por separado.)

---

## Micronaut 4.5

```java
@io.micronaut.http.annotation.Controller
public class BenchController {
  @io.micronaut.http.annotation.Get(value="/plaintext", produces="text/plain")
  public String plaintext(){ return "OK"; }
  @io.micronaut.http.annotation.Get(value="/json", produces="application/json")
  public String json(){ return "{\"message\":\"hello\",\"n\":42}"; }
}
```
Deps: `micronaut-http-server-netty`. Build/run: `mvn -q clean package && java -jar target/app.jar`.

---

## Javalin 6 (Jetty)

```java
public class App {
  public static void main(String[] a){
    io.javalin.Javalin app = io.javalin.Javalin.create().start(8080);
    app.get("/plaintext", ctx -> ctx.contentType("text/plain").result("OK"));
    app.get("/json", ctx -> ctx.contentType("application/json").result("{\"message\":\"hello\",\"n\":42}"));
  }
}
```
Deps: `io.javalin:javalin:6.x` (+ slf4j-simple). Build fat-jar con `maven-shade-plugin`.
Run: `java -jar target/app.jar`.

---

### Notas de paridad
- Sin logging de acceso por request (desactivar) en todos.
- Mismo pool de hilos por defecto del framework (documentar si se ajusta).
- Respuestas idénticas byte-a-byte en `/json`.
