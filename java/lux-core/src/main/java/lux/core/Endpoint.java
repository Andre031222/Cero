package lux.core;

@FunctionalInterface
public interface Endpoint {

    Object handle(Context context) throws Exception;
}
