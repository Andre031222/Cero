package cero.core;

@FunctionalInterface
public interface Endpoint {

    Object handle(Context context) throws Exception;
}
