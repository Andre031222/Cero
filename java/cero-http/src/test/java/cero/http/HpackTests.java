package cero.http;

import java.util.List;

/**
 * HPACK contra los vectores del apéndice C del RFC 7541.
 *
 * <p>No son casos inventados: son los ejemplos que el propio RFC da con su volcado hexadecimal,
 * incluidas las tres peticiones seguidas que enseñan cómo crece la tabla dinámica entre una y
 * otra. Es la única forma de saber que el decodificador está de acuerdo con el resto del mundo y
 * no solo consigo mismo.
 */
final class HpackTests {

    private HpackTests() {
    }

    static void run() {
        Check.group("HPACK · vectores del RFC 7541");

        tablas();
        enteros();
        huffman();
        peticionesSinHuffman();
        peticionesConHuffman();
        respuestasConTablaPequena();
        idaYVuelta();
        entradasHostiles();
    }

    private static byte[] hex(String texto) {
        String limpio = texto.replaceAll("[^0-9a-fA-F]", "");
        byte[] salida = new byte[limpio.length() / 2];
        for (int i = 0; i < salida.length; i++) {
            salida[i] = (byte) Integer.parseInt(limpio.substring(i * 2, i * 2 + 2), 16);
        }
        return salida;
    }

    private static String texto(List<Hpack.Campo> campos) {
        StringBuilder s = new StringBuilder();
        for (Hpack.Campo c : campos) {
            s.append(c.nombre()).append(": ").append(c.valor()).append('\n');
        }
        return s.toString();
    }

    private static void tablas() {
        Check.equal("la tabla estática tiene 61 entradas", HpackTablas.ESTATICA.length, 61);
        Check.equal("la 1 es :authority", HpackTablas.ESTATICA[0][0], ":authority");
        Check.equal("la 61 es www-authenticate", HpackTablas.ESTATICA[60][0], "www-authenticate");
        Check.equal("Huffman tiene 257 símbolos con EOS", HpackTablas.CODIGO.length, 257);

        // Kraft: la suma de 2^-longitud de un código prefijo completo vale exactamente 1. Si la
        // tabla se copió mal —un símbolo de menos, una longitud cambiada— esto no da 1.
        double kraft = 0;
        for (byte largo : HpackTablas.LARGO) {
            kraft += Math.pow(2, -largo);
        }
        Check.that("la suma de Kraft del código vale 1: es prefijo y está completo",
                Math.abs(kraft - 1.0) < 1e-12);
    }

    /** §C.1: 10 y 1337 con prefijo de cinco bits, y 42 empezando en octeto. */
    private static void enteros() {
        Check.equal("10 con prefijo de 5", leer(hex("0a"), 5), 10);
        Check.equal("1337 con prefijo de 5", leer(hex("1f9a0a"), 5), 1337);
        Check.equal("42 en octeto entero", leer(hex("2a"), 8), 42);
    }

    private static int leer(byte[] datos, int prefijo) {
        Hpack.Lector in = new Hpack.Lector(datos);
        in.leer();
        return Hpack.leerEntero(in, prefijo);
    }

    private static void huffman() {
        // §C.4.1: «www.example.com» comprimido son doce octetos.
        byte[] codificado = hex("f1e3 c2e5 f23a 6ba0 ab90 f4ff");
        Check.equal("decodifica www.example.com",
                Hpack.huffmanDecodificar(codificado), "www.example.com");
        Check.that("y codificarlo devuelve los mismos bytes",
                java.util.Arrays.equals(Hpack.huffmanCodificar("www.example.com"), codificado));

        // §C.6.1: una fecha, que es donde Huffman más ahorra.
        Check.equal("decodifica una fecha",
                Hpack.huffmanDecodificar(hex("d07a be94 1054 d444 a820 0595 040b 8166 e082 a62d 1bff")),
                "Mon, 21 Oct 2013 20:13:21 GMT");
    }

    /** §C.3: tres peticiones seguidas por la misma conexión, sin Huffman. */
    private static void peticionesSinHuffman() {
        Hpack.Decodificador d = new Hpack.Decodificador(Hpack.TAMANO_TABLA);

        Check.equal("C.3.1 · primera petición",
                texto(d.decodificar(hex("8286 8441 0f77 7777 2e65 7861 6d70 6c65 2e63 6f6d"))),
                ":method: GET\n:scheme: http\n:path: /\n:authority: www.example.com\n");
        Check.equal("y deja una entrada en la tabla dinámica", d.entradas(), 1);

        Check.equal("C.3.2 · la segunda reusa lo que dejó la primera",
                texto(d.decodificar(hex("8286 84be 5808 6e6f 2d63 6163 6865"))),
                ":method: GET\n:scheme: http\n:path: /\n:authority: www.example.com\n"
                        + "cache-control: no-cache\n");
        Check.equal("y ahora hay dos", d.entradas(), 2);

        Check.equal("C.3.3 · y la tercera, las dos anteriores",
                texto(d.decodificar(hex("8287 85bf 400a 6375 7374 6f6d 2d6b 6579 0c63"
                        + "7573 746f 6d2d 7661 6c75 65"))),
                ":method: GET\n:scheme: https\n:path: /index.html\n:authority: www.example.com\n"
                        + "custom-key: custom-value\n");
        Check.equal("y tres", d.entradas(), 3);
    }

    /** §C.4: las mismas tres, ahora con las cadenas comprimidas. */
    private static void peticionesConHuffman() {
        Hpack.Decodificador d = new Hpack.Decodificador(Hpack.TAMANO_TABLA);

        Check.equal("C.4.1 · primera petición",
                texto(d.decodificar(hex("8286 8441 8cf1 e3c2 e5f2 3a6b a0ab 90f4 ff"))),
                ":method: GET\n:scheme: http\n:path: /\n:authority: www.example.com\n");
        Check.equal("C.4.2 · segunda",
                texto(d.decodificar(hex("8286 84be 5886 a8eb 1064 9cbf"))),
                ":method: GET\n:scheme: http\n:path: /\n:authority: www.example.com\n"
                        + "cache-control: no-cache\n");
        Check.equal("C.4.3 · tercera",
                texto(d.decodificar(hex("8287 85bf 4088 25a8 49e9 5ba9 7d7f 8925 a849 e95b"
                        + "b8e8 b4bf"))),
                ":method: GET\n:scheme: https\n:path: /index.html\n:authority: www.example.com\n"
                        + "custom-key: custom-value\n");
    }

    /**
     * §C.5: respuestas con la tabla limitada a 256 bytes, que es donde se ve la expulsión.
     * En la tercera, la tabla ya ha tirado entradas de la primera.
     */
    private static void respuestasConTablaPequena() {
        Hpack.Decodificador d = new Hpack.Decodificador(Hpack.TAMANO_TABLA);
        d.decodificar(hex("3f e1 1f"));   // §6.3: bajar la tabla a 256

        Check.equal("C.5.1 · primera respuesta",
                texto(d.decodificar(hex("4803 3330 3258 0770 7269 7661 7465 611d 4d6f 6e2c"
                        + "2032 3120 4f63 7420 3230 3133 2032 303a 3133 3a32"
                        + "3120 474d 546e 1768 7474 7073 3a2f 2f77 7777 2e65"
                        + "7861 6d70 6c65 2e63 6f6d"))),
                "location: https://www.example.com\n".transform(s -> ":status: 302\n"
                        + "cache-control: private\n"
                        + "date: Mon, 21 Oct 2013 20:13:21 GMT\n" + s));

        Check.equal("C.5.2 · segunda, casi toda por índice",
                texto(d.decodificar(hex("4803 3330 37c1 c0bf"))),
                ":status: 307\ncache-control: private\n"
                        + "date: Mon, 21 Oct 2013 20:13:21 GMT\n"
                        + "location: https://www.example.com\n");

        Check.equal("C.5.3 · tercera, con la tabla ya expulsando",
                texto(d.decodificar(hex("88c1 611d 4d6f 6e2c 2032 3120 4f63 7420 3230 3133"
                        + "2032 303a 3133 3a32 3220 474d 54c0 5a04 677a 6970"
                        + "7738 666f 6f3d 4153 444a 4b48 514b 425a 584f 5157"
                        + "454f 5049 5541 5851 5745 4f49 553b 206d 6178 2d61"
                        + "6765 3d33 3630 303b 2076 6572 7369 6f6e 3d31"))),
                ":status: 200\ncache-control: private\n"
                        + "date: Mon, 21 Oct 2013 20:13:22 GMT\n"
                        + "location: https://www.example.com\n"
                        + "content-encoding: gzip\n"
                        + "set-cookie: foo=ASDJKHQKBZXOQWEOPIUAXQWEOIU; max-age=3600; version=1\n");
    }

    /** Lo que este servidor emite tiene que poder leerlo su propio decodificador. */
    private static void idaYVuelta() {
        List<Hpack.Campo> campos = List.of(
                new Hpack.Campo(":status", "200"),
                new Hpack.Campo("content-type", "text/html; charset=utf-8"),
                new Hpack.Campo("content-length", "1234"),
                new Hpack.Campo("x-medida", "acentós y eñes no, pero sí símbolos ~!#$%&"),
                new Hpack.Campo("x-vacia", ""));

        byte[] bloque = new Hpack.Codificador().codificar(campos);
        List<Hpack.Campo> vuelta = new Hpack.Decodificador(Hpack.TAMANO_TABLA).decodificar(bloque);
        Check.equal("lo codificado se vuelve a leer igual", texto(vuelta), texto(campos));
        Check.that("y ocupa menos que el texto plano equivalente",
                bloque.length < texto(campos).length());
    }

    /** Lo que manda un cliente roto, o uno que no lo está siendo por accidente. */
    private static void entradasHostiles() {
        rechaza("índice 0, que no existe", "80");
        rechaza("índice más allá de la tabla", "ff00");
        rechaza("cadena que dice ser más larga que el bloque", "00 0f 61");
        rechaza("entero de longitud desmedida", "1f ff ff ff ff ff ff ff ff ff 7f");
        rechaza("bloque cortado a medias", "41");
        rechaza("EOS dentro de una cadena", "0000 84ff ffff ff");
        rechaza("tabla dinámica mayor que la acordada", "3f e2 ff 03");
    }

    private static void rechaza(String nombre, String hexadecimal) {
        try {
            new Hpack.Decodificador(Hpack.TAMANO_TABLA).decodificar(hex(hexadecimal));
            Check.that("se rechaza: " + nombre, false);
        } catch (Hpack.Rota esperado) {
            Check.that("se rechaza: " + nombre, true);
        } catch (RuntimeException otro) {
            Check.that("se rechaza con Rota y no con " + otro.getClass().getSimpleName()
                    + ": " + nombre, false);
        }
    }
}
