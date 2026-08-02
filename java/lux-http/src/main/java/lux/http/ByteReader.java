package lux.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class ByteReader {

    private final InputStream source;
    private final byte[] buffer;
    private int position;
    private int limit;

    ByteReader(InputStream source, int capacity) {
        this.source = source;
        this.buffer = new byte[capacity];
    }

    boolean hasBuffered() {
        return position < limit;
    }

    int read() throws IOException {
        if (position == limit && !fill()) {
            return -1;
        }
        return buffer[position++] & 0xFF;
    }

    int read(byte[] target, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (position == limit) {
            if (length >= buffer.length) {
                return source.read(target, offset, length);
            }
            if (!fill()) {
                return -1;
            }
        }
        int available = Math.min(length, limit - position);
        System.arraycopy(buffer, position, target, offset, available);
        position += available;
        return available;
    }

    String readLine(int maxBytes) throws IOException {
        byte[] overflow = null;
        int overflowLength = 0;

        while (true) {
            if (position == limit && !fill()) {
                if (overflowLength == 0) {
                    return null;
                }
                throw new HttpException(400, "línea incompleta");
            }

            int newline = indexOfNewline();
            int end = newline >= 0 ? newline : limit;
            int chunk = end - position;

            if (overflowLength + chunk > maxBytes) {
                throw new HttpException(431, "línea demasiado larga");
            }

            if (newline >= 0 && overflow == null) {
                int length = chunk;
                if (length > 0 && buffer[end - 1] == '\r') {
                    length--;
                }
                String line = new String(buffer, position, length, StandardCharsets.ISO_8859_1);
                position = newline + 1;
                return line;
            }

            if (overflow == null) {
                overflow = new byte[Math.max(128, chunk * 2)];
            } else if (overflowLength + chunk > overflow.length) {
                overflow = Arrays.copyOf(overflow, Math.max(overflow.length * 2, overflowLength + chunk));
            }
            System.arraycopy(buffer, position, overflow, overflowLength, chunk);
            overflowLength += chunk;
            position = end;

            if (newline >= 0) {
                position = newline + 1;
                int length = overflowLength;
                if (length > 0 && overflow[length - 1] == '\r') {
                    length--;
                }
                return new String(overflow, 0, length, StandardCharsets.ISO_8859_1);
            }
        }
    }

    private int indexOfNewline() {
        for (int i = position; i < limit; i++) {
            if (buffer[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private boolean fill() throws IOException {
        position = 0;
        limit = source.read(buffer, 0, buffer.length);
        if (limit <= 0) {
            limit = 0;
            return false;
        }
        return true;
    }
}
