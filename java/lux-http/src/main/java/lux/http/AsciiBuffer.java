package lux.http;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Buffer reutilizable para armar la cabecera de la respuesta sin asignar nada. Vive en la
 * conexión, así que se reaprovecha entre peticiones. No es seguro entre hilos, ni falta: una
 * conexión la atiende un solo hilo virtual.
 */
final class AsciiBuffer {

    private byte[] bytes;
    private int size;

    AsciiBuffer(int capacity) {
        bytes = new byte[capacity];
    }

    void reset() {
        size = 0;
    }

    AsciiBuffer put(char c) {
        asegurar(1);
        bytes[size++] = (byte) c;
        return this;
    }

    /** ISO-8859-1, que es lo que admite una cabecera; lo que no quepa en un byte pasa a {@code ?}. */
    AsciiBuffer put(String text) {
        int length = text.length();
        asegurar(length);
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            bytes[size++] = (byte) (c <= 0xFF ? c : '?');
        }
        return this;
    }

    /** Decimal sin pasar por {@code Long.toString}. Solo no negativos. */
    AsciiBuffer put(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("se esperaba un número no negativo: " + value);
        }
        int start = size;
        do {
            asegurar(1);
            bytes[size++] = (byte) ('0' + (int) (value % 10));
            value /= 10;
        } while (value > 0);
        for (int i = start, j = size - 1; i < j; i++, j--) {
            byte swap = bytes[i];
            bytes[i] = bytes[j];
            bytes[j] = swap;
        }
        return this;
    }

    AsciiBuffer crlf() {
        asegurar(2);
        bytes[size++] = '\r';
        bytes[size++] = '\n';
        return this;
    }

    void writeTo(OutputStream out) throws IOException {
        out.write(bytes, 0, size);
    }

    int size() {
        return size;
    }

    private void asegurar(int extra) {
        if (size + extra > bytes.length) {
            bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, size + extra));
        }
    }
}
