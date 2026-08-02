package lux.http;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

public final class Tls {

    private Tls() {
    }

    public static SSLContext fromKeystore(Path keystore, char[] password) {
        return fromKeystore(keystore, password, "PKCS12");
    }

    public static SSLContext fromKeystore(Path keystore, char[] password, String type) {
        try (InputStream source = Files.newInputStream(keystore)) {
            KeyStore store = KeyStore.getInstance(type);
            store.load(source, password);

            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, password);

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);
            return context;
        } catch (IOException cause) {
            throw new UncheckedIOException("no se pudo leer el keystore " + keystore, cause);
        } catch (GeneralSecurityException cause) {
            throw new IllegalStateException("keystore inválido: " + keystore, cause);
        }
    }
}
