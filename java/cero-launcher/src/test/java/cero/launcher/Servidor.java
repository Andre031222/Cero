package cero.launcher;

import cero.http.Server;
import cero.http.ServerOptions;

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
