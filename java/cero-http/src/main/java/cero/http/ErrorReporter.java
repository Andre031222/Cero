package cero.http;

public interface ErrorReporter {

    void handler(Request request, Throwable failure);

    void transport(Throwable failure);

    static ErrorReporter standardError() {
        return new ErrorReporter() {
            @Override
            public void handler(Request request, Throwable failure) {
                System.err.println("cero-http: fallo en " + request.method() + " " + request.path());
                failure.printStackTrace(System.err);
            }

            @Override
            public void transport(Throwable failure) {
            }
        };
    }

    static ErrorReporter silent() {
        return new ErrorReporter() {
            @Override
            public void handler(Request request, Throwable failure) {
            }

            @Override
            public void transport(Throwable failure) {
            }
        };
    }
}
