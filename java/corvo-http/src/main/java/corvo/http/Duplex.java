package corvo.http;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * La conexión en crudo, después de cambiar de protocolo. Quien la recibe es dueño de leerla y
 * escribirla hasta que termine; el servidor ya no vuelve a tocarla y la cierra al volver.
 */
public interface Duplex {

    InputStream in();

    OutputStream out();

    /** La petición que pidió el cambio, por si el nuevo protocolo necesita mirarla. */
    Request request();
}
