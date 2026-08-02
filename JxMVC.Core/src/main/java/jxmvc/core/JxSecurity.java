/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

/**
 * Registro global del proveedor de autenticación.
 * Cuando no se configura proveedor, {@code @JxAuth} no tiene efecto.
 */
public final class JxSecurity {

    private static volatile JxAuthProvider provider;

    private JxSecurity() {}

    public static void setProvider(JxAuthProvider p) { provider = p; }
    public static JxAuthProvider getProvider()       { return provider; }
    public static boolean isConfigured()             { return provider != null; }
}
