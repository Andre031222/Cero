package cero.http;

class Conforme {

    void main(String[] args) throws InterruptedException {
        var opciones = ServerOptions.builder().port(args.length > 0 ? Integer.parseInt(args[0]) : 8777);
        if (args.length > 1) {
            int plazo = Integer.parseInt(args[1]);
            opciones.idleTimeoutMillis(2 * plazo).writeTimeoutMillis(plazo);
        }
        Server.start(opciones.build(), Conforme::rutas).await();
    }

    private static void rutas(Request peticion, Response respuesta) throws Exception {
        switch (peticion.path()) {
            case "/eco" -> respuesta.send(peticion.bodyBytes());
            case "/grande" -> respuesta.text("x".repeat(1 << 20));
            case "/descarga" -> respuesta.text("x".repeat(32 << 20));
            default -> respuesta.text("cero, conforme\n");
        }
    }
}
