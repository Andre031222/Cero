/// JxMVC Open-source project 2024 - 2026
/// -------------------------------------------
///  coded by : Dr. Ramiro Pedro Laura Murillo

package jxmvc.core;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Hashing de contraseñas para el login nativo — cero dependencias externas
 * (usa {@link SecretKeyFactory} PBKDF2 de la JDK).
 *
 * <p>Cada hash es autocontenido y portable, con este formato:
 * <pre>
 *   pbkdf2$sha256$&lt;iteraciones&gt;$&lt;saltB64&gt;$&lt;hashB64&gt;
 * </pre>
 * El salt (16 bytes aleatorios) y el número de iteraciones viajan dentro del propio
 * hash, de modo que {@link #verify} no necesita configuración para validarlo y el
 * coste puede subirse sin migrar los hashes existentes.
 *
 * <pre>
 *   String stored = JxPasswords.hash("s3cr3t");        // al registrar
 *   boolean ok    = JxPasswords.verify("s3cr3t", stored); // al iniciar sesión
 * </pre>
 */
public final class JxPasswords {

    private static final String ALGORITHM   = "PBKDF2WithHmacSHA256";
    private static final String PREFIX       = "pbkdf2$sha256$";
    private static final int    SALT_BYTES   = 16;
    private static final int    KEY_BITS     = 256;
    private static final int    DEFAULT_ITER = 210_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private JxPasswords() {}

    /** Deriva un hash PBKDF2-SHA256 con salt aleatorio y el coste por defecto. */
    public static String hash(String plain) {
        return hash(plain, DEFAULT_ITER);
    }

    /** Deriva un hash con un número de iteraciones explícito (coste configurable). */
    public static String hash(String plain, int iterations) {
        if (plain == null) throw new IllegalArgumentException("La contraseña no puede ser null");
        if (iterations < 1) throw new IllegalArgumentException("iterations debe ser >= 1");

        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] dk = derive(plain.toCharArray(), salt, iterations, KEY_BITS);

        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return PREFIX + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(dk);
    }

    /**
     * Verifica una contraseña contra un hash previamente producido por {@link #hash}.
     * La comparación es en tiempo constante y cualquier hash malformado retorna
     * {@code false} en vez de lanzar.
     */
    public static boolean verify(String plain, String stored) {
        if (plain == null || stored == null || !stored.startsWith(PREFIX)) return false;

        String[] parts = stored.split("\\$");
        if (parts.length != 5) return false;

        try {
            int    iterations = Integer.parseInt(parts[2]);
            byte[] salt       = Base64.getDecoder().decode(parts[3]);
            byte[] expected   = Base64.getDecoder().decode(parts[4]);
            byte[] actual     = derive(plain.toCharArray(), salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Indica si un hash debería re-generarse por usar menos iteraciones que el coste
     * actual del framework. Útil para reforzar hashes antiguos tras un login válido.
     */
    public static boolean needsRehash(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return true;
        String[] parts = stored.split("\\$");
        if (parts.length != 5) return true;
        try {
            return Integer.parseInt(parts[2]) < DEFAULT_ITER;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private static byte[] derive(char[] plain, byte[] salt, int iterations, int keyBits) {
        try {
            KeySpec spec = new PBEKeySpec(plain, salt, iterations, keyBits);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 no disponible en esta JVM", e);
        } finally {
            java.util.Arrays.fill(plain, '\0');
        }
    }
}
