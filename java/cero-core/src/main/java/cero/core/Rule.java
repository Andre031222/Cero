package cero.core;

public interface Rule<T> {

    boolean test(T value);

    default String message() {
        return "no cumple la regla " + getClass().getSimpleName();
    }
}
