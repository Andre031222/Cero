package corvo.launcher;

import corvo.http.Server;
import corvo.http.ServerOptions;

/** La aplicación que se empaqueta en la prueba: el jar tiene que poder arrancarla solo. */
public final class Servidor {

    private Servidor() {
    }

    public static void main(String[] args) throws Exception {
        int puerto = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Server servidor = Server.start(ServerOptions.builder().port(puerto).build(),
                (peticion, respuesta) -> respuesta.text("empaquetado"));
        servidor.await();
    }
}
