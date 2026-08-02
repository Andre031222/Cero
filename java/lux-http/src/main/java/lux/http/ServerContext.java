package lux.http;

import java.util.function.BooleanSupplier;

record ServerContext(
        ServerOptions options,
        Handler handler,
        ErrorReporter reporter,
        Watchdog watchdog,
        Sessions sessions,
        BooleanSupplier accepting) {
}
