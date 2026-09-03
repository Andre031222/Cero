package cero.core;

@FunctionalInterface
public interface Authenticator {

    Principal authenticate(Context context);
}
