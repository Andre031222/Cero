package cero.http;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class HttpDate {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

    private static volatile long cachedSecond = -1;
    private static volatile String cachedValue = "";

    private HttpDate() {
    }

    static String format(long epochMillis) {
        return FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    static String now() {
        long second = System.currentTimeMillis() / 1000;
        if (second != cachedSecond) {
            cachedValue = FORMAT.format(Instant.ofEpochSecond(second));
            cachedSecond = second;
        }
        return cachedValue;
    }
}
