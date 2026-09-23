package cero.test;

import java.net.http.HttpClient;
import java.util.Map;

final class SesionTests {

    private SesionTests() {
    }

    static void run() {
        Check.group("la sesión sobrevive a la segunda petición");

        try (TestServer app = TestServer.start(Demo.app())) {
            for (HttpClient.Version version : HttpClient.Version.values()) {
                TestClient client = app.client();
                if (version == HttpClient.Version.HTTP_1_1) {
                    client.http1();
                } else {
                    client.http2();
                }

                client.get("/yo").status(401);

                client.form("/acceso", Map.of("usuario", "ada")).ok().cookie("CEROSESSION");
                Check.that("la cookie de sesión queda guardada [" + version + "]",
                        client.cookie("CEROSESSION") != null);

                client.get("/yo").ok().json("usuario", "ada");
                Check.that("la segunda petición sigue autenticada [" + version + "]", true);
            }

            Check.that("un cliente nuevo no hereda la sesión",
                    app.client().get("/yo").status(401).status() == 401);
        }
    }
}
