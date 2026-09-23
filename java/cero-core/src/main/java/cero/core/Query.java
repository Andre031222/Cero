package cero.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Query {
    /** Centinela de «no se declaró defecto»: la cadena vacía es un valor legítimo de orElse. */
    String SIN_DEFECTO = "\u0000cero.sin-defecto";

    String value();

    String orElse() default SIN_DEFECTO;
}
