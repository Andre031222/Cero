import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Carga con cabeceras de navegador real: 11, no 3. Es lo que ejercita el parseo de cabeceras. */
public class CargaReal {
    public static void main(String[] a) throws Exception {
        int conns = Integer.parseInt(a[0]), segs = Integer.parseInt(a[1]);
        byte[] pet = ("GET / HTTP/1.1\r\nHost: 127.0.0.1:8777\r\n"
            + "User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36\r\n"
            + "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8\r\n"
            + "Accept-Language: es-PE,es;q=0.9,en;q=0.8\r\nAccept-Encoding: gzip, deflate\r\n"
            + "Connection: keep-alive\r\nUpgrade-Insecure-Requests: 1\r\n"
            + "Sec-Fetch-Site: none\r\nSec-Fetch-Mode: navigate\r\nSec-Fetch-Dest: document\r\n"
            + "Cache-Control: max-age=0\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        long fin = System.nanoTime() + segs * 1_000_000_000L;
        java.util.concurrent.atomic.AtomicLong total = new java.util.concurrent.atomic.AtomicLong();
        Thread[] hs = new Thread[conns];
        for (int i = 0; i < conns; i++) {
            hs[i] = new Thread(() -> {
                try (Socket s = new Socket("127.0.0.1", 8777)) {
                    s.setTcpNoDelay(true);
                    OutputStream o = s.getOutputStream();
                    InputStream in = s.getInputStream();
                    byte[] buf = new byte[8192];
                    long n = 0;
                    while (System.nanoTime() < fin) {
                        o.write(pet); o.flush();
                        int leidos = in.read(buf);
                        if (leidos <= 0) break;
                        n++;
                    }
                    total.addAndGet(n);
                } catch (IOException e) { }
            });
            hs[i].start();
        }
        for (Thread h : hs) h.join();
        System.out.printf("rps=%.0f%n", total.get() / (double) segs);
    }
}
