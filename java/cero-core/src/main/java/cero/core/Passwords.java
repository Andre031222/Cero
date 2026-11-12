package cero.core;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

public final class Passwords {

    private static final String ALGORITMO = "PBKDF2WithHmacSHA256";
    private static final String PREFIJO = "pbkdf2$sha256$";
    private static final int ITERACIONES = 210_000;
    private static final int BYTES_SAL = 16;
    private static final int BITS_CLAVE = 256;

    private static final SecureRandom ALEATORIO = new SecureRandom();
    private static final Base64.Encoder CODIFICADOR = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder DECODIFICADOR = Base64.getDecoder();

    private Passwords() {
    }

    public static String hash(String clara) {
        return hash(clara, ITERACIONES);
    }

    public static String hash(String clara, int iteraciones) {
        if (clara == null || clara.isEmpty()) {
            throw new IllegalArgumentException("la contraseña no puede estar vacía");
        }
        byte[] sal = new byte[BYTES_SAL];
        ALEATORIO.nextBytes(sal);
        byte[] derivada = derivar(clara, sal, iteraciones);
        return PREFIJO + iteraciones + "$" + CODIFICADOR.encodeToString(sal)
                + "$" + CODIFICADOR.encodeToString(derivada);
    }

    public static boolean verify(String clara, String guardado) {
        if (clara == null || guardado == null || !guardado.startsWith(PREFIJO)) {
            return false;
        }
        String[] partes = guardado.split("\\$");
        if (partes.length != 5) {
            return false;
        }
        try {
            int iteraciones = Integer.parseInt(partes[2]);
            byte[] sal = DECODIFICADOR.decode(partes[3]);
            byte[] esperado = DECODIFICADOR.decode(partes[4]);
            return MessageDigest.isEqual(esperado, derivar(clara, sal, iteraciones));
        } catch (RuntimeException ilegible) {
            return false;
        }
    }

    public static boolean needsRehash(String guardado) {
        if (guardado == null || !guardado.startsWith(PREFIJO)) {
            return true;
        }
        String[] partes = guardado.split("\\$");
        try {
            return partes.length != 5 || Integer.parseInt(partes[2]) < ITERACIONES;
        } catch (NumberFormatException ilegible) {
            return true;
        }
    }

    public static int iterations() {
        return ITERACIONES;
    }

    private static byte[] derivar(String clara, byte[] sal, int iteraciones) {
        var especificacion = new PBEKeySpec(clara.toCharArray(), sal, iteraciones, BITS_CLAVE);
        try {
            return SecretKeyFactory.getInstance(ALGORITMO).generateSecret(especificacion).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException fallo) {
            throw new IllegalStateException("no se pudo derivar la contraseña", fallo);
        } finally {
            especificacion.clearPassword();
        }
    }
}
