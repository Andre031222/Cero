package jxmvc.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

public class GzipTest {

    static int passed = 0;
    static int failed = 0;

    static void ok(String label) { passed++; System.out.println("  OK  " + label); }
    static void fail(String label, String msg) { failed++; System.out.println("  FAIL " + label + ": " + msg); }
    static void check(String label, boolean cond) { if (cond) ok(label); else fail(label, "false"); }

    public static void main(String[] args) throws Exception {
        testSmallNotCompressed();
        testMediumCompressed();
        testLargePassthrough();
        testWriterPath();
        System.out.printf("GzipTest: pass=%d fail=%d%n", passed, failed);
    }

    static void testSmallNotCompressed() throws Exception {
        var st = new TestStubs.ResponseState();
        var wrapper = new JxGzip.GzipWrapper(TestStubs.response(st), 1024 * 1024);
        byte[] data = "hola".getBytes(StandardCharsets.UTF_8);
        wrapper.setContentType("text/html");
        wrapper.getOutputStream().write(data);
        wrapper.finish(TestStubs.response(st), 860);
        check("pequeña sin Content-Encoding", !st.headers.containsKey("Content-Encoding"));
        check("pequeña íntegra", Arrays.equals(st.out.toByteArray(), data));
    }

    static void testMediumCompressed() throws Exception {
        var st = new TestStubs.ResponseState();
        var wrapper = new JxGzip.GzipWrapper(TestStubs.response(st), 1024 * 1024);
        byte[] data = "x".repeat(2000).getBytes(StandardCharsets.UTF_8);
        wrapper.setContentType("text/html");
        wrapper.getOutputStream().write(data);
        wrapper.finish(TestStubs.response(st), 860);
        check("mediana con Content-Encoding gzip", "gzip".equals(st.headers.get("Content-Encoding")));
        byte[] decompressed = new GZIPInputStream(
                new ByteArrayInputStream(st.out.toByteArray())).readAllBytes();
        check("mediana descomprime al original", Arrays.equals(decompressed, data));
    }

    static void testLargePassthrough() throws Exception {
        var st = new TestStubs.ResponseState();
        var wrapper = new JxGzip.GzipWrapper(TestStubs.response(st), 1000);
        byte[] data = "y".repeat(5000).getBytes(StandardCharsets.UTF_8);
        wrapper.setContentType("text/html");
        var out = wrapper.getOutputStream();
        for (int i = 0; i < data.length; i += 500) out.write(data, i, 500);
        wrapper.finish(TestStubs.response(st), 860);
        check("grande sin Content-Encoding (passthrough)", !st.headers.containsKey("Content-Encoding"));
        check("grande íntegra en passthrough", Arrays.equals(st.out.toByteArray(), data));
    }

    static void testWriterPath() throws Exception {
        var st = new TestStubs.ResponseState();
        var wrapper = new JxGzip.GzipWrapper(TestStubs.response(st), 1024 * 1024);
        String text = "z".repeat(2000);
        wrapper.setContentType("application/json");
        wrapper.getWriter().write(text);
        wrapper.finish(TestStubs.response(st), 860);
        byte[] decompressed = new GZIPInputStream(
                new ByteArrayInputStream(st.out.toByteArray())).readAllBytes();
        check("writer comprime y descomprime al original",
                text.equals(new String(decompressed, StandardCharsets.UTF_8)));
    }
}
