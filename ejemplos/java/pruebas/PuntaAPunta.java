import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
int fallos = 0;

void main(String[] args) throws Exception {
    String jar = args.length > 0 ? args[0] : "target/ejemplo.jar";
    int puerto;
    try (var libre = new ServerSocket(0)) {
        puerto = libre.getLocalPort();
    }
    String java = ProcessHandle.current().info().command().orElse("java");
    var arranque = new ProcessBuilder(java, "-jar", jar).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD);
    arranque.environment().put("PUERTO", String.valueOf(puerto));
    Process ejemplo = arranque.start();
    try {
        esperar(puerto, ejemplo);
        comprueba(pedir(puerto, "GET", "/", null), 200, "{\"hola\":\"mundo\"}", "GET /");
        comprueba(pedir(puerto, "GET", "/saludo/ana", null), 200, "{\"saludo\":\"hola, ana\"}", "GET /saludo/ana");
        String json = "{\"a\":[1,2],\"b\":{\"c\":\"ñ\"}}";
        comprueba(pedir(puerto, "POST", "/eco", json), 200, json, "POST /eco");
        comprueba(pedir(puerto, "POST", "/eco", "\"x\""), 200, "\"x\"", "POST /eco con una cadena");
        var mala = pedir(puerto, "POST", "/eco", "no es json");
        comprueba(mala, 400, mala.body(), "POST /eco sin JSON da 400");
    } finally {
        ejemplo.destroy();
        ejemplo.waitFor();
    }
    System.exit(fallos == 0 ? 0 : 1);
}

void esperar(int puerto, Process ejemplo) throws Exception {
    long limite = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (true) {
        try {
            pedir(puerto, "GET", "/", null);
            return;
        } catch (IOException noTodavia) {
            if (!ejemplo.isAlive() || System.nanoTime() > limite) {
                throw new IllegalStateException("el ejemplo no arrancó en :" + puerto);
            }
            Thread.sleep(50);
        }
    }
}

HttpResponse<String> pedir(int puerto, String metodo, String camino, String cuerpo) throws Exception {
    var envio = cuerpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(cuerpo);
    var peticion = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto + camino))
            .header("Content-Type", "application/json")
            .method(metodo, envio)
            .build();
    return cliente.send(peticion, HttpResponse.BodyHandlers.ofString());
}

void comprueba(HttpResponse<String> respuesta, int estado, String cuerpo, String que) {
    boolean bien = respuesta.statusCode() == estado && respuesta.body().equals(cuerpo);
    System.out.println("  " + (bien ? "ok " : "x  ") + que + (bien ? "" : " · " + respuesta.statusCode() + " " + respuesta.body()));
    if (!bien) {
        fallos++;
    }
}
