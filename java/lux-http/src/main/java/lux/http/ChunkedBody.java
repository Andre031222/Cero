package lux.http;

import java.io.IOException;
import java.io.InputStream;

final class ChunkedBody extends InputStream {

    private final ByteReader reader;
    private final long maxBytes;
    private final int maxLineBytes;
    private long consumed;
    private long chunkRemaining;
    private boolean started;
    private boolean finished;

    ChunkedBody(ByteReader reader, long maxBytes, int maxLineBytes) {
        this.reader = reader;
        this.maxBytes = maxBytes;
        this.maxLineBytes = maxLineBytes;
    }

    @Override
    public int read() throws IOException {
        if (!advance()) {
            return -1;
        }
        int value = reader.read();
        if (value < 0) {
            throw new HttpException(400, "cuerpo chunked truncado");
        }
        chunkRemaining--;
        return value;
    }

    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (!advance()) {
            return -1;
        }
        int wanted = (int) Math.min(length, chunkRemaining);
        int read = reader.read(target, offset, wanted);
        if (read < 0) {
            throw new HttpException(400, "cuerpo chunked truncado");
        }
        chunkRemaining -= read;
        return read;
    }

    boolean drained() {
        return finished;
    }

    private boolean advance() throws IOException {
        if (finished) {
            return false;
        }
        if (chunkRemaining > 0) {
            return true;
        }
        if (started) {
            expectEmptyLine();
        }
        started = true;

        long size = readChunkSize();
        if (size == 0) {
            skipTrailer();
            finished = true;
            return false;
        }
        consumed += size;
        if (consumed > maxBytes) {
            throw new HttpException(413, "cuerpo demasiado grande");
        }
        chunkRemaining = size;
        return true;
    }

    private long readChunkSize() throws IOException {
        String line = reader.readLine(maxLineBytes);
        if (line == null) {
            throw new HttpException(400, "cuerpo chunked truncado");
        }
        int end = line.indexOf(';');
        String digits = (end < 0 ? line : line.substring(0, end)).trim();
        if (digits.isEmpty()) {
            throw new HttpException(400, "tamaño de chunk vacío");
        }
        try {
            long size = Long.parseLong(digits, 16);
            if (size < 0) {
                throw new HttpException(400, "tamaño de chunk negativo");
            }
            return size;
        } catch (NumberFormatException cause) {
            throw new HttpException(400, "tamaño de chunk inválido: " + digits);
        }
    }

    private void expectEmptyLine() throws IOException {
        String line = reader.readLine(maxLineBytes);
        if (line == null || !line.isEmpty()) {
            throw new HttpException(400, "separador de chunk inválido");
        }
    }

    private void skipTrailer() throws IOException {
        String line;
        while ((line = reader.readLine(maxLineBytes)) != null && !line.isEmpty()) {
            continue;
        }
    }
}
