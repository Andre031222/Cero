package corvo.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** La carpeta {@code frontend/} de un proyecto generado con {@code --front}. */
final class Frontend {

    private Frontend() {
    }

    static int escribir(Path raiz, GeneradorProyecto.Peticion peticion) throws IOException {
        Path front = raiz.resolve("frontend");
        Files.createDirectories(front.resolve("src"));

        escribir(front.resolve("LEEME.md"), leeme(peticion));
        escribir(front.resolve("src").resolve("api.js"), api());
        escribir(raiz.resolve("LEEME.md"), raizLeeme(peticion));
        return 3;
    }

    /**
     * Deja el backend listo para hablar con un frontend aparte: CORS para los puertos de
     * desarrollo habituales, y la carpeta desde la que se servirá lo compilado.
     */
    static void cablearBackend(Path backend, GeneradorProyecto.Peticion peticion) throws IOException {
        Path recursos = backend.resolve("src/main/resources");
        Files.createDirectories(recursos.resolve("front"));
        escribir(recursos.resolve("front").resolve(".gitkeep"),
                "Aquí se copia lo que compile el frontend.\n");

        Path fuentes = backend.resolve("src/main/java").resolve(peticion.paquete().replace('.', '/'));
        escribir(fuentes.resolve("App.java"), appConFrontend(peticion));
        escribir(fuentes.resolve("InicioController.java"), apiConFrontend(peticion));

        // Las plantillas y la hoja de estilo son del backend que sirve HTML; aquí lo pinta el
        // frontend, y dejarlas confundiría a quien abra el proyecto.
        borrarSiEstan(backend.resolve("src/main/resources/plantillas"));
        borrarSiEstan(backend.resolve("src/main/resources/estaticos"));
    }

    private static void borrarSiEstan(Path directorio) throws IOException {
        if (!Files.isDirectory(directorio)) {
            return;
        }
        try (var contenido = Files.walk(directorio)) {
            for (Path camino : contenido.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(camino);
            }
        }
    }

    private static String apiConFrontend(GeneradorProyecto.Peticion p) {
        return """
                package %s;

                import corvo.core.Get;
                import corvo.core.Result;
                import corvo.core.Route;

                import java.util.Map;

                @Route("/api")
                public class InicioController {

                    @Get("/salud")
                    public Result salud() {
                        return Result.json(Map.of("estado", "ok", "app", "%s"));
                    }
                }
                """.formatted(p.paquete(), p.nombre());
    }

    private static String appConFrontend(GeneradorProyecto.Peticion p) {
        return """
                package %s;

                import corvo.core.Cors;
                import corvo.core.Corvo;
                import corvo.http.StaticFiles;

                public final class App {

                    public static void main(String[] args) throws Exception {
                        Corvo.app()
                           .port(args.length > 0 ? Integer.parseInt(args[0]) : 8080)
                           // En desarrollo el frontend corre en su propio puerto, así que es otro
                           // origen. En producción van en el mismo jar y esto no interviene.
                           .use(Cors.allowing("http://localhost:5173", "http://localhost:3000"))
                           .controllers(InicioController.class)
                           // spa() devuelve index.html en las rutas que no son archivos, para que
                           // un router de navegador funcione al entrar directo. Con una
                           // exportación estática que ya trae un archivo por ruta, se puede quitar.
                           .fallback(StaticFiles.fromClasspath("front").spa())
                           .start()
                           .await();
                    }
                }
                """.formatted(p.paquete());
    }

    private static void escribir(Path destino, String contenido) throws IOException {
        Files.writeString(destino, contenido, StandardCharsets.UTF_8);
    }

    private static String raizLeeme(GeneradorProyecto.Peticion p) {
        return """
                # %s

                    backend/     Corvo — la API, en Java
                    frontend/    tu interfaz — React, Svelte, Vue, lo que prefieras

                ## Desarrollo

                Dos servidores a la vez. El backend en el 8080:

                ```bash
                cd backend && mvn -q package && java -jar target/%s.jar
                ```

                Y el frontend en el suyo, como te pida la herramienta que uses. El backend ya
                acepta peticiones desde `http://localhost:5173` y `http://localhost:3000`.

                ## Producción

                Se compila el frontend, su salida se copia a
                `backend/src/main/resources/front/`, y sale **un solo jar** que sirve las dos
                cosas. Sin nginx delante para unirlos y sin dos despliegues.

                ```bash
                cd frontend && npm run build && cp -r dist/* ../backend/src/main/resources/front/
                cd ../backend && mvn -q package
                java -jar target/%s.jar
                ```
                """.formatted(p.nombre(), p.artefacto(), p.artefacto());
    }

    private static String leeme(GeneradorProyecto.Peticion p) {
        return """
                # Frontend de %s

                Esta carpeta está vacía a propósito: elige tú la herramienta.

                ```bash
                npm create vite@latest . -- --template react
                npm create vite@latest . -- --template svelte
                npx create-next-app@latest .
                ```

                ## Lo único que hay que saber

                **En desarrollo** el backend corre aparte, en el 8080, y ya acepta peticiones
                desde los puertos habituales de Vite y Next. `src/api.js` tiene un cliente mínimo.

                **En producción** copia lo compilado a `../backend/src/main/resources/front/`.
                Cómo se llama esa carpeta de salida depende de la herramienta: `dist/` en Vite,
                `out/` en Next con exportación estática, `build/` en otras.

                ## Rutas de cliente

                Si tu router vive en el navegador —React Router, el de Svelte—, el backend tiene
                que devolver `index.html` para las rutas que no son archivos. Ya viene puesto:

                ```java
                StaticFiles.fromClasspath("front").spa()
                ```

                Con Next.js exportado estáticamente no hace falta: cada ruta es su propia carpeta
                con un `index.html` dentro, y se encuentra sola.
                """.formatted(p.nombre());
    }

    private static String api() {
        return """
                const BASE = import.meta?.env?.DEV ? 'http://localhost:8080' : '';

                async function pedir(ruta, opciones = {}) {
                  const respuesta = await fetch(BASE + ruta, {
                    headers: { 'Content-Type': 'application/json' },
                    credentials: 'include',
                    ...opciones,
                  });
                  if (!respuesta.ok) {
                    throw new Error(`${respuesta.status} en ${ruta}`);
                  }
                  return respuesta.status === 204 ? null : respuesta.json();
                }

                export const api = {
                  salud: () => pedir('/api/salud'),
                  get: (ruta) => pedir(ruta),
                  post: (ruta, datos) => pedir(ruta, { method: 'POST', body: JSON.stringify(datos) }),
                };
                """;
    }
}
