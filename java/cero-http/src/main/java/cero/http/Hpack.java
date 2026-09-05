package cero.http;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HPACK (RFC 7541): la compresión de cabeceras de HTTP/2.
 *
 * <p>Existe porque en HTTP/2 las cabeceras no viajan como texto. Son un formato binario con una
 * tabla estática de 61 entradas frecuentes, una tabla dinámica que crece con lo que ya se ha
 * mandado por esa conexión, y codificación de Huffman opcional para las cadenas.
 *
 * <p>Las dos tablas fijas están en {@link HpackTablas}, generadas del RFC. Aquí va la lógica.
 *
 * <p><b>La tabla dinámica es estado compartido de la conexión y va en un solo sentido.</b> Si el
 * decodificador se desincroniza del codificador del cliente, no falla la cabecera: fallan todas
 * las siguientes, porque los índices dejan de significar lo mismo. Por eso cualquier error aquí
 * es un error de conexión y no de flujo — no se puede seguir.
 */
final class Hpack {

    /** Tope por defecto de la tabla dinámica, en «bytes» según la cuenta del RFC. */
    static final int TAMANO_TABLA = 4096;

    /** Un par nombre/valor. El tamaño que ocupa lo define el RFC, no la memoria real. */
    record Campo(String nombre, String valor) {

        /** RFC 7541 §4.1: nombre + valor + 32 de sobrecarga fija. */
        int coste() {
            return nombre.length() + valor.length() + 32;
        }
    }

    /** Un fallo de compresión. Obliga a cerrar la conexión entera, no solo el flujo. */
    static final class Rota extends RuntimeException {

        Rota(String mensaje) {
            super(mensaje);
        }
    }

    // ─── enteros de longitud variable (§5.1) ─────────────────────────────────────────────────

    /** Lee un entero con {@code prefijo} bits útiles en el primer octeto. */
    static int leerEntero(Lector in, int prefijo) {
        int tope = (1 << prefijo) - 1;
        int valor = in.byteActual() & tope;
        if (valor < tope) {
            return valor;
        }
        int desplazamiento = 0;
        while (true) {
            int b = in.leer();
            // Siete bits por octeto: a partir de 28 el siguiente ya no cabe en un int, y un
            // entero desmedido es la forma clásica de agotar memoria por una cabecera.
            if (desplazamiento > 28) {
                throw new Rota("entero HPACK desmedido");
            }
            valor += (b & 0x7F) << desplazamiento;
            if (valor < 0) {
                throw new Rota("entero HPACK desmedido");
            }
            if ((b & 0x80) == 0) {
                return valor;
            }
            desplazamiento += 7;
        }
    }

    static void escribirEntero(List<Byte> salida, int valor, int prefijo, int banderas) {
        int tope = (1 << prefijo) - 1;
        if (valor < tope) {
            salida.add((byte) (banderas | valor));
            return;
        }
        salida.add((byte) (banderas | tope));
        int resto = valor - tope;
        while (resto >= 0x80) {
            salida.add((byte) ((resto & 0x7F) | 0x80));
            resto >>>= 7;
        }
        salida.add((byte) resto);
    }

    // ─── Huffman (§5.2) ──────────────────────────────────────────────────────────────────────

    /**
     * El árbol de decodificación, montado una vez a partir de la tabla del RFC.
     *
     * <p>Se recorre bit a bit. Podría hacerse por tablas de varios bits a la vez, que es más
     * rápido, pero esto son cabeceras —decenas de bytes— y un árbol se lee y se comprueba;
     * una tabla de saltos precalculada, no.
     */
    private static final int[] IZQUIERDA;
    private static final int[] DERECHA;
    private static final int[] SIMBOLO;

    static {
        int nodos = 1;
        for (int i = 0; i < HpackTablas.CODIGO.length; i++) {
            nodos += HpackTablas.LARGO[i];
        }
        IZQUIERDA = new int[nodos];
        DERECHA = new int[nodos];
        SIMBOLO = new int[nodos];
        java.util.Arrays.fill(IZQUIERDA, -1);
        java.util.Arrays.fill(DERECHA, -1);
        java.util.Arrays.fill(SIMBOLO, -1);

        int siguiente = 1;
        for (int simbolo = 0; simbolo < HpackTablas.CODIGO.length; simbolo++) {
            int codigo = HpackTablas.CODIGO[simbolo];
            int largo = HpackTablas.LARGO[simbolo];
            int nodo = 0;
            for (int bit = largo - 1; bit >= 0; bit--) {
                boolean uno = ((codigo >>> bit) & 1) != 0;
                int hijo = uno ? DERECHA[nodo] : IZQUIERDA[nodo];
                if (hijo < 0) {
                    hijo = siguiente++;
                    if (uno) {
                        DERECHA[nodo] = hijo;
                    } else {
                        IZQUIERDA[nodo] = hijo;
                    }
                }
                nodo = hijo;
            }
            SIMBOLO[nodo] = simbolo;
        }
    }

    static String huffmanDecodificar(byte[] datos) {
        StringBuilder salida = new StringBuilder(datos.length * 8 / 5);
        int nodo = 0;
        int bitsDeRelleno = 0;
        for (byte b : datos) {
            for (int bit = 7; bit >= 0; bit--) {
                boolean uno = ((b >>> bit) & 1) != 0;
                nodo = uno ? DERECHA[nodo] : IZQUIERDA[nodo];
                if (nodo < 0) {
                    throw new Rota("código de Huffman que no existe");
                }
                bitsDeRelleno = uno ? bitsDeRelleno + 1 : 0;
                if (SIMBOLO[nodo] >= 0) {
                    if (SIMBOLO[nodo] == HpackTablas.EOS) {
                        // §5.2: EOS decodificado es un error, no un final. Aceptarlo deja que
                        // dos cadenas distintas produzcan el mismo texto.
                        throw new Rota("EOS dentro de una cadena Huffman");
                    }
                    salida.append((char) SIMBOLO[nodo]);
                    nodo = 0;
                    bitsDeRelleno = 0;
                }
            }
        }
        // El relleno es el prefijo de EOS, o sea unos, y como mucho siete bits. Cualquier otra
        // cosa —quedarse a medias de un símbolo, o rellenar con más de siete— está prohibida.
        if (nodo != 0 && (bitsDeRelleno > 7 || bitsDeRelleno == 0)) {
            throw new Rota("relleno de Huffman inválido");
        }
        return salida.toString();
    }

    static byte[] huffmanCodificar(String texto) {
        long acumulador = 0;
        int bits = 0;
        java.io.ByteArrayOutputStream salida = new java.io.ByteArrayOutputStream(texto.length());
        for (int i = 0; i < texto.length(); i++) {
            int c = texto.charAt(i) & 0xFF;
            acumulador = (acumulador << HpackTablas.LARGO[c]) | (HpackTablas.CODIGO[c] & 0xFFFFFFFFL);
            bits += HpackTablas.LARGO[c];
            while (bits >= 8) {
                bits -= 8;
                salida.write((int) (acumulador >>> bits) & 0xFF);
            }
        }
        if (bits > 0) {
            // Se rellena con unos, que es el prefijo de EOS.
            salida.write((int) ((acumulador << (8 - bits)) | ((1 << (8 - bits)) - 1)) & 0xFF);
        }
        return salida.toByteArray();
    }

    static int huffmanBits(String texto) {
        int bits = 0;
        for (int i = 0; i < texto.length(); i++) {
            bits += HpackTablas.LARGO[texto.charAt(i) & 0xFF];
        }
        return bits;
    }

    // ─── lector sobre el bloque de cabeceras ─────────────────────────────────────────────────

    static final class Lector {

        private final byte[] datos;
        private int pos;
        private int actual;

        Lector(byte[] datos) {
            this.datos = datos;
        }

        boolean hay() {
            return pos < datos.length;
        }

        int leer() {
            if (pos >= datos.length) {
                throw new Rota("bloque de cabeceras cortado");
            }
            actual = datos[pos++] & 0xFF;
            return actual;
        }

        int byteActual() {
            return actual;
        }

        byte[] leerBytes(int cuantos) {
            if (cuantos < 0 || pos + cuantos > datos.length) {
                throw new Rota("cadena HPACK más larga que el bloque");
            }
            byte[] trozo = new byte[cuantos];
            System.arraycopy(datos, pos, trozo, 0, cuantos);
            pos += cuantos;
            return trozo;
        }
    }

    // ─── decodificador ───────────────────────────────────────────────────────────────────────

    /** Un decodificador por conexión: la tabla dinámica es suya y dura lo que la conexión. */
    static final class Decodificador {

        private final ArrayDeque<Campo> dinamica = new ArrayDeque<>();
        private int coste;
        private int tope = TAMANO_TABLA;
        private final int topeMaximo;

        Decodificador(int topeMaximo) {
            this.topeMaximo = topeMaximo;
        }

        List<Campo> decodificar(byte[] bloque) {
            List<Campo> campos = new ArrayList<>();
            Lector in = new Lector(bloque);
            while (in.hay()) {
                int b = in.leer();
                if ((b & 0x80) != 0) {                       // §6.1 indexado
                    int i = leerEntero(in, 7);
                    if (i == 0) {
                        throw new Rota("índice 0 no existe");
                    }
                    campos.add(enIndice(i));
                } else if ((b & 0x40) != 0) {                // §6.2.1 literal, se indexa
                    Campo c = literal(in, 6);
                    indexar(c);
                    campos.add(c);
                } else if ((b & 0x20) != 0) {                // §6.3 cambio de tamaño
                    int nuevo = leerEntero(in, 5);
                    if (nuevo > topeMaximo) {
                        throw new Rota("la tabla dinámica pedida supera lo acordado");
                    }
                    tope = nuevo;
                    podar();
                } else {                                      // §6.2.2 y §6.2.3 literal sin indexar
                    campos.add(literal(in, 4));
                }
            }
            return campos;
        }

        private Campo literal(Lector in, int prefijo) {
            int indice = leerEntero(in, prefijo);
            String nombre = indice == 0 ? cadena(in) : enIndice(indice).nombre();
            return new Campo(nombre, cadena(in));
        }

        private String cadena(Lector in) {
            int b = in.leer();
            boolean huffman = (b & 0x80) != 0;
            int largo = leerEntero(in, 7);
            byte[] crudo = in.leerBytes(largo);
            if (huffman) {
                return huffmanDecodificar(crudo);
            }
            return new String(crudo, java.nio.charset.StandardCharsets.ISO_8859_1);
        }

        private Campo enIndice(int indice) {
            if (indice <= HpackTablas.ESTATICA.length) {
                String[] e = HpackTablas.ESTATICA[indice - 1];
                return new Campo(e[0], e[1]);
            }
            int enDinamica = indice - HpackTablas.ESTATICA.length - 1;
            if (enDinamica >= dinamica.size()) {
                throw new Rota("índice " + indice + " fuera de la tabla");
            }
            int i = 0;
            for (Campo c : dinamica) {
                if (i++ == enDinamica) {
                    return c;
                }
            }
            throw new Rota("índice " + indice + " fuera de la tabla");
        }

        private void indexar(Campo c) {
            // §4.4: una entrada más grande que la tabla entera no es un error — la vacía.
            if (c.coste() > tope) {
                dinamica.clear();
                coste = 0;
                return;
            }
            dinamica.addFirst(c);
            coste += c.coste();
            podar();
        }

        private void podar() {
            while (coste > tope && !dinamica.isEmpty()) {
                coste -= dinamica.removeLast().coste();
            }
        }

        int entradas() {
            return dinamica.size();
        }
    }

    // ─── codificador ─────────────────────────────────────────────────────────────────────────

    /**
     * Codificador sin tabla dinámica propia.
     *
     * <p>Manda todo como literal sin indexar, usando la tabla estática cuando el nombre está en
     * ella y Huffman cuando ahorra. Es una decisión, no una carencia: mantener tabla dinámica de
     * salida obliga a llevar su estado en paralelo al del cliente, y un desajuste ahí rompe la
     * conexión entera de forma difícil de ver. Lo que se pierde es unos bytes por respuesta; lo
     * que se gana es que no exista esa clase de fallo.
     */
    static final class Codificador {

        byte[] codificar(List<Campo> campos) {
            List<Byte> salida = new ArrayList<>(campos.size() * 24);
            for (Campo c : campos) {
                String nombre = c.nombre().toLowerCase(Locale.ROOT);
                int indice = indiceEstaticoDeNombre(nombre);
                // 0x10: literal que nunca se indexa (§6.2.3), coherente con no llevar tabla.
                escribirEntero(salida, indice, 4, 0x10);
                if (indice == 0) {
                    cadena(salida, nombre);
                }
                cadena(salida, c.valor());
            }
            byte[] bytes = new byte[salida.size()];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = salida.get(i);
            }
            return bytes;
        }

        private void cadena(List<Byte> salida, String texto) {
            int bitsHuffman = huffmanBits(texto);
            boolean vale = (bitsHuffman + 7) / 8 < texto.length();
            byte[] datos = vale
                    ? huffmanCodificar(texto)
                    : texto.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            escribirEntero(salida, datos.length, 7, vale ? 0x80 : 0x00);
            for (byte b : datos) {
                salida.add(b);
            }
        }

        private static int indiceEstaticoDeNombre(String nombre) {
            for (int i = 0; i < HpackTablas.ESTATICA.length; i++) {
                if (HpackTablas.ESTATICA[i][0].equals(nombre)) {
                    return i + 1;
                }
            }
            return 0;
        }
    }

    private Hpack() {
    }
}
