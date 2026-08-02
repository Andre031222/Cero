package jxmvc.core;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.*;
import java.util.zip.GZIPOutputStream;

/**
 * Compresión GZIP opt-in.
 * Activar: {@code jxmvc.gzip.enabled=true} en {@code application.properties}.
 *
 * <pre>
 * # application.properties
 * jxmvc.gzip.enabled=true
 * jxmvc.gzip.minBytes=860
 * jxmvc.gzip.maxBytes=1048576
 * </pre>
 *
 * Respuestas que superan {@code maxBytes} se envían sin comprimir en streaming
 * directo, evitando retener respuestas grandes en memoria.
 */
final class JxGzip {

    private static final JxLogger log = JxLogger.getLogger(JxGzip.class);

    static final boolean ENABLED;
    static final int     MIN_BYTES;
    static final int     MAX_BYTES;

    static {
        ENABLED   = "true".equalsIgnoreCase(BaseDbResolver.property("jxmvc.gzip.enabled", "false"));
        MIN_BYTES = BaseDbResolver.propertyInt("jxmvc.gzip.minBytes", 860);
        MAX_BYTES = BaseDbResolver.propertyInt("jxmvc.gzip.maxBytes", 1024 * 1024);
        if (ENABLED) log.info("GZIP habilitado (minBytes={}, maxBytes={})", MIN_BYTES, MAX_BYTES);
    }

    private JxGzip() {}

    static final class GzipWrapper extends HttpServletResponseWrapper {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(2048);
        private final int         maxBytes;
        private PrintWriter       writer;
        private SwitchingStream   stream;
        private String            contentType;
        private boolean           passthrough;

        GzipWrapper(HttpServletResponse r) { this(r, MAX_BYTES); }

        GzipWrapper(HttpServletResponse r, int maxBytes) {
            super(r);
            this.maxBytes = maxBytes;
        }

        @Override public void setContentType(String t) { contentType = t; super.setContentType(t); }

        @Override public PrintWriter getWriter() throws IOException {
            if (stream != null && writer == null) throw new IllegalStateException("getOutputStream ya fue llamado");
            if (writer == null) {
                stream = new SwitchingStream();
                writer = new PrintWriter(new OutputStreamWriter(stream, "UTF-8"));
            }
            return writer;
        }

        @Override public ServletOutputStream getOutputStream() {
            if (writer != null) throw new IllegalStateException("getWriter ya fue llamado");
            if (stream == null) stream = new SwitchingStream();
            return stream;
        }

        @Override public void setContentLength(int l)      {}
        @Override public void setContentLengthLong(long l) {}

        void finish(HttpServletResponse resp, int minBytes) throws IOException {
            if (writer != null) writer.flush();
            if (passthrough) { resp.getOutputStream().flush(); return; }
            byte[] data = buffer.toByteArray();
            if (data.length == 0) return;

            if (data.length >= minBytes && isText(contentType)) {
                ByteArrayOutputStream gz = new ByteArrayOutputStream(data.length / 2);
                try (GZIPOutputStream gos = new GZIPOutputStream(gz)) { gos.write(data); }
                byte[] compressed = gz.toByteArray();
                resp.setHeader("Content-Encoding", "gzip");
                resp.setHeader("Vary", "Accept-Encoding");
                resp.setContentLength(compressed.length);
                resp.getOutputStream().write(compressed);
            } else {
                resp.setContentLength(data.length);
                resp.getOutputStream().write(data);
            }
        }

        private void writeBytes(byte[] b, int off, int len) throws IOException {
            if (!passthrough && buffer.size() + len > maxBytes) switchToPassthrough();
            if (passthrough) super.getResponse().getOutputStream().write(b, off, len);
            else             buffer.write(b, off, len);
        }

        private void switchToPassthrough() throws IOException {
            passthrough = true;
            buffer.writeTo(super.getResponse().getOutputStream());
            buffer.reset();
        }

        private static boolean isText(String ct) {
            if (ct == null) return false;
            String l = ct.toLowerCase();
            return l.startsWith("text/") || l.contains("json") || l.contains("xml");
        }

        private final class SwitchingStream extends ServletOutputStream {
            @Override public void write(int b) throws IOException {
                writeBytes(new byte[]{(byte) b}, 0, 1);
            }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                writeBytes(b, off, len);
            }
            @Override public boolean isReady()                       { return true; }
            @Override public void setWriteListener(WriteListener w)  {}
        }
    }
}
