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
