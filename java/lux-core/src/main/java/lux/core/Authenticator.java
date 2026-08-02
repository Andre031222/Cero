package lux.core;

@FunctionalInterface
public interface Authenticator {

    Principal authenticate(Context context);
}
