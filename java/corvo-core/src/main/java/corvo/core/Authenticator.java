package corvo.core;

@FunctionalInterface
public interface Authenticator {

    Principal authenticate(Context context);
}
