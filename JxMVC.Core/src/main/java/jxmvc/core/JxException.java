/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

/**
 * Excepción HTTP tipada. Lanzar desde cualquier acción del controller.
 *
 * <pre>
 *   throw JxException.notFound("Usuario no existe");
 *   throw JxException.unauthorized("Inicia sesión primero");
 *   throw new JxException(422, "Datos inválidos");
 * </pre>
 */
public class JxException extends RuntimeException {

    private final int status;

    public JxException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() { return status; }

    public static JxException badRequest(String msg)   { return new JxException(400, msg); }
    public static JxException unauthorized(String msg) { return new JxException(401, msg); }
    public static JxException forbidden(String msg)    { return new JxException(403, msg); }
    public static JxException notFound(String msg)     { return new JxException(404, msg); }
    public static JxException conflict(String msg)     { return new JxException(409, msg); }
    public static JxException serverError(String msg)  { return new JxException(500, msg); }
}
