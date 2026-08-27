package corvo.core;

@FunctionalInterface
public interface Middleware {

    Object handle(Context context, Chain chain) throws Exception;

    @FunctionalInterface
    interface Chain {
        Object proceed(Context context) throws Exception;
    }
}
