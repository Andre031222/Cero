package cero.http;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicReference;

public final class Tls {

    private Tls() {
    }

    public static SSLContext fromKeystore(Path keystore, char[] password) {
        return fromKeystore(keystore, password, "PKCS12");
    }

    public static SSLContext fromKeystore(Path keystore, char[] password, String type) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(new KeyManager[] {leerClaves(keystore, password, type)}, null, null);
            return context;
        } catch (GeneralSecurityException cause) {
            throw new IllegalStateException("keystore inválido: " + keystore, cause);
        }
    }

    /**
     * Un contexto TLS cuyo certificado se puede cambiar sin reiniciar el servidor.
     *
     * <pre>
     *   Tls.Certificado cert = Tls.reloadable(Path.of("cert.p12"), "clave".toCharArray());
     *   Server servidor = Server.start(opciones.tls(cert.context()), handler);
     *
     *   cert.reload();   // tras renovarlo, sin cortar nada
     * </pre>
     *
     * <p>El {@link SSLContext} es siempre el mismo objeto —el socket de escucha se creó con él—
     * pero por dentro consulta el certificado en cada apretón de manos. Las conexiones ya
     * abiertas siguen con el anterior; las nuevas usan el nuevo.
     */
    public static Certificado reloadable(Path keystore, char[] password) {
        return new Certificado(keystore, password, "PKCS12");
    }

    public static Certificado reloadable(Path keystore, char[] password, String type) {
        return new Certificado(keystore, password, type);
    }

    public static final class Certificado {

        private final Path keystore;
        private final char[] password;
        private final String type;
        private final AtomicReference<X509ExtendedKeyManager> actual = new AtomicReference<>();
        private final SSLContext context;

        private Certificado(Path keystore, char[] password, String type) {
            this.keystore = keystore;
            this.password = password.clone();
            this.type = type;
            this.actual.set(leerClaves(keystore, password, type));
            try {
                SSLContext creado = SSLContext.getInstance("TLS");
                creado.init(new KeyManager[] {new Delegante(actual)}, null, null);
                this.context = creado;
            } catch (GeneralSecurityException cause) {
                throw new IllegalStateException("no se pudo montar el contexto TLS", cause);
            }
        }

        public SSLContext context() {
            return context;
        }

        public Path keystore() {
            return keystore;
        }

        /** Relee el archivo. Si el nuevo no sirve, deja el anterior en pie y lanza. */
        public void reload() {
            actual.set(leerClaves(keystore, password, type));
        }
    }

    private static X509ExtendedKeyManager leerClaves(Path keystore, char[] password, String type) {
        try (InputStream source = Files.newInputStream(keystore)) {
            KeyStore store = KeyStore.getInstance(type);
            store.load(source, password);

            KeyManagerFactory fabrica =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            fabrica.init(store, password);

            for (KeyManager manager : fabrica.getKeyManagers()) {
                if (manager instanceof X509ExtendedKeyManager claves) {
                    return claves;
                }
            }
            throw new IllegalStateException("el keystore no trae claves X.509: " + keystore);
        } catch (IOException cause) {
            throw new UncheckedIOException("no se pudo leer el keystore " + keystore, cause);
        } catch (GeneralSecurityException cause) {
            throw new IllegalStateException("keystore inválido: " + keystore, cause);
        }
    }

    /** Pregunta por el certificado en cada apretón de manos, en lugar de quedárselo. */
    private static final class Delegante extends X509ExtendedKeyManager {

        private final AtomicReference<X509ExtendedKeyManager> fuente;

        Delegante(AtomicReference<X509ExtendedKeyManager> fuente) {
            this.fuente = fuente;
        }

        @Override
        public String[] getClientAliases(String tipoClave, Principal[] emisores) {
            return fuente.get().getClientAliases(tipoClave, emisores);
        }

        @Override
        public String chooseClientAlias(String[] tiposClave, Principal[] emisores, Socket socket) {
            return fuente.get().chooseClientAlias(tiposClave, emisores, socket);
        }

        @Override
        public String[] getServerAliases(String tipoClave, Principal[] emisores) {
            return fuente.get().getServerAliases(tipoClave, emisores);
        }

        @Override
        public String chooseServerAlias(String tipoClave, Principal[] emisores, Socket socket) {
            return fuente.get().chooseServerAlias(tipoClave, emisores, socket);
        }

        @Override
        public String chooseEngineClientAlias(String[] tiposClave, Principal[] emisores, SSLEngine motor) {
            return fuente.get().chooseEngineClientAlias(tiposClave, emisores, motor);
        }

        @Override
        public String chooseEngineServerAlias(String tipoClave, Principal[] emisores, SSLEngine motor) {
            return fuente.get().chooseEngineServerAlias(tipoClave, emisores, motor);
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return fuente.get().getCertificateChain(alias);
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return fuente.get().getPrivateKey(alias);
        }
    }
}
