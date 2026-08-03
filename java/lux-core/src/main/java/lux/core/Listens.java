package lux.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca un método como oyente de eventos. El tipo del evento es el de su único parámetro.
 *
 * <pre>
 *   &#64;Service
 *   class Avisos {
 *
 *       &#64;Listens
 *       void alDarseDeAlta(UsuarioCreado evento) {
 *           correo.bienvenida(evento.email());
 *       }
 *   }
 * </pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Listens {
}
