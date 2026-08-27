package corvo.http;

import java.io.IOException;
import java.io.InputStream;

final class FixedBody extends InputStream {

    private final ByteReader reader;
    private long remaining;

    FixedBody(ByteReader reader, long length) {
        this.reader = reader;
        this.remaining = length;
    }

    @Override
    public int read() throws IOException {
        if (remaining == 0) {
            return -1;
        }
        int value = reader.read();
        if (value < 0) {
            throw new HttpException(400, "cuerpo truncado");
        }
        remaining--;
        return value;
    }

    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
        if (remaining == 0) {
            return -1;
        }
        int wanted = (int) Math.min(length, remaining);
        int read = reader.read(target, offset, wanted);
        if (read < 0) {
            throw new HttpException(400, "cuerpo truncado");
        }
        remaining -= read;
        return read;
    }

    @Override
    public int available() {
        return (int) Math.min(remaining, Integer.MAX_VALUE);
    }

    boolean drained() {
        return remaining == 0;
    }
}
