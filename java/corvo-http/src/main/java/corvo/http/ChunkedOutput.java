package corvo.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class ChunkedOutput extends OutputStream {

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] TERMINATOR = "0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private final OutputStream target;
    private boolean closed;

    ChunkedOutput(OutputStream target) {
        this.target = target;
    }

    @Override
    public void write(int value) throws IOException {
        write(new byte[]{(byte) value}, 0, 1);
    }

    @Override
    public void write(byte[] source, int offset, int length) throws IOException {
        if (closed) {
            throw new IOException("flujo cerrado");
        }
        if (length == 0) {
            return;
        }
        target.write(Integer.toHexString(length).getBytes(StandardCharsets.ISO_8859_1));
        target.write(CRLF);
        target.write(source, offset, length);
        target.write(CRLF);
    }

    @Override
    public void flush() throws IOException {
        target.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        target.write(TERMINATOR);
        target.flush();
    }
}
