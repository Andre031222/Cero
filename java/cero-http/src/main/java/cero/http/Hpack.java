package cero.http;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Hpack {

    static final int TAMANO_TABLA = 4096;
    static final int MAX_CAMPOS = 256;

    record Campo(String nombre, String valor) {

        int coste() {
            return nombre.length() + valor.length() + 32;
        }
    }

    static final class Rota extends RuntimeException {

        Rota(String mensaje) {
            super(mensaje);
        }
    }

    static final class Desmedida extends RuntimeException {

        Desmedida(String mensaje) {
            super(mensaje);
        }
    }

    static int leerEntero(Lector in, int prefijo) {
        int tope = (1 << prefijo) - 1;
        int valor = in.byteActual() & tope;
        if (valor < tope) {
            return valor;
        }
        int desplazamiento = 0;
        while (true) {
            int b = in.leer();
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
                        throw new Rota("EOS dentro de una cadena Huffman");
                    }
                    salida.append((char) SIMBOLO[nodo]);
                    nodo = 0;
                    bitsDeRelleno = 0;
                }
            }
        }
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

    static final class Decodificador {

        private final ArrayDeque<Campo> dinamica = new ArrayDeque<>();
        private int coste;
        private int tope = TAMANO_TABLA;
        private final int topeMaximo;
        private final int maxLista;
        private final int maxCampos;

        Decodificador(int topeMaximo, int maxLista, int maxCampos) {
            this.topeMaximo = topeMaximo;
            this.maxLista = maxLista;
            this.maxCampos = maxCampos;
        }

        List<Campo> decodificar(byte[] bloque) {
            List<Campo> campos = new ArrayList<>();
            Lector in = new Lector(bloque);
            boolean todaviaAlPrincipio = true;
            long lista = 0;
            while (in.hay()) {
                int b = in.leer();
                Campo c;
                if ((b & 0x80) != 0) {
                    int i = leerEntero(in, 7);
                    if (i == 0) {
                        throw new Rota("índice 0 no existe");
                    }
                    c = enIndice(i);
                } else if ((b & 0x40) != 0) {
                    c = literal(in, 6);
                    indexar(c);
                } else if ((b & 0x20) != 0) {
                    if (!todaviaAlPrincipio) {
                        throw new Rota("cambio de tamaño de tabla fuera del principio del bloque");
                    }
                    int nuevo = leerEntero(in, 5);
                    if (nuevo > topeMaximo) {
                        throw new Rota("la tabla dinámica pedida supera lo acordado");
                    }
                    tope = nuevo;
                    podar();
                    continue;
                } else {
                    c = literal(in, 4);
                }
                todaviaAlPrincipio = false;
                lista += c.coste();
                if (lista > maxLista) {
                    throw new Desmedida("la lista de cabeceras se pasa del máximo al expandirse");
                }
                if (campos.size() == maxCampos) {
                    throw new Desmedida("más campos de los que se admiten");
                }
                campos.add(c);
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

    static final class Codificador {

        byte[] codificar(List<Campo> campos) {
            List<Byte> salida = new ArrayList<>(campos.size() * 24);
            for (Campo c : campos) {
                String nombre = c.nombre().toLowerCase(Locale.ROOT);
                int indice = indiceEstaticoDeNombre(nombre);
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
