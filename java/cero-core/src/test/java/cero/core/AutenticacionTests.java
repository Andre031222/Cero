package cero.core;

import cero.http.ErrorReporter;
import cero.http.Server;

import java.math.BigInteger;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AutenticacionTests {

    private AutenticacionTests() {
    }

    private static final Base64.Encoder URL64 = Base64.getUrlEncoder().withoutPadding();

    static void run() throws Exception {
        Check.group("contraseñas");
        contrasenas();

        Check.group("OAuth · autorización");
        autorizacion();

        Check.group("OAuth · Google de punta a punta");
        googleCompleto();
    }

    private static void contrasenas() {
        String guardado = Passwords.hash("clave-secreta-123");

        Check.that("el formato lleva el algoritmo", guardado.startsWith("pbkdf2$sha256$"));
        Check.equal("y cinco segmentos", guardado.split("\\$").length, 5);
        Check.that("lleva embebido el coste", guardado.contains("$" + Passwords.iterations() + "$"));

        Check.that("verifica la correcta", Passwords.verify("clave-secreta-123", guardado));
        Check.that("rechaza la incorrecta", !Passwords.verify("otra-clave", guardado));
        Check.that("rechaza vacía", !Passwords.verify("", guardado));
        Check.that("rechaza null", !Passwords.verify(null, guardado));
        Check.that("rechaza un hash null", !Passwords.verify("x", null));

        Check.that("dos hashes de la misma clave difieren",
                !Passwords.hash("misma").equals(Passwords.hash("misma")));
        Check.that("pero ambos verifican",
                Passwords.verify("misma", Passwords.hash("misma"))
                        && Passwords.verify("misma", Passwords.hash("misma")));

        Check.that("un hash basura no revienta", !Passwords.verify("x", "no-es-un-hash"));
        Check.that("segmentos incompletos tampoco", !Passwords.verify("x", "pbkdf2$sha256$1000"));
        Check.that("iteraciones no numéricas tampoco",
                !Passwords.verify("x", "pbkdf2$sha256$muchas$c2Fs$aGFzaA"));

        String barato = Passwords.hash("clave", 1_000);
        Check.that("verifica con coste bajo", Passwords.verify("clave", barato));
        Check.that("y pide rehacerse", Passwords.needsRehash(barato));
        Check.that("el coste actual no pide rehacerse", !Passwords.needsRehash(guardado));
        Check.that("un formato desconocido pide rehacerse", Passwords.needsRehash("md5$loquesea"));

        Check.raises("no admite contraseña vacía", IllegalArgumentException.class,
                () -> Passwords.hash(""));
    }

    private static void autorizacion() {
        OAuth google = OAuth.google("id-cliente.apps.googleusercontent.com", "secreto",
                "https://miapp.pe/entrar/google");
        OAuth.Salida salida = google.autorizar();

        Check.that("apunta al endpoint de Google",
                salida.url().startsWith("https://accounts.google.com/o/oauth2/v2/auth?"));
        Check.that("lleva el identificador de cliente",
                salida.url().contains("client_id=id-cliente.apps.googleusercontent.com"));
        Check.that("y la redirección escapada",
                salida.url().contains("redirect_uri=https%3A%2F%2Fmiapp.pe%2Fentrar%2Fgoogle"));
        Check.that("pide un código", salida.url().contains("response_type=code"));
        Check.that("con los alcances de OpenID",
                salida.url().contains("scope=openid+email+profile"));
        Check.that("lleva estado contra CSRF", salida.url().contains("state=" + salida.estado()));

        Check.that("usa PKCE con S256", salida.url().contains("code_challenge_method=S256"));
        Check.that("y manda el reto, no el verificador",
                salida.url().contains("code_challenge=")
                        && !salida.url().contains(salida.verificador()));
        Check.that("el verificador es suficientemente largo", salida.verificador().length() >= 43);
        Check.that("Google pide consentimiento explícito",
                salida.url().contains("access_type=offline"));

        Check.that("dos llamadas dan verificadores distintos",
                !google.autorizar().verificador().equals(salida.verificador()));
        Check.that("y estados distintos",
                !google.autorizar().estado().equals(salida.estado()));

        Check.that("Microsoft apunta a su propio endpoint",
                OAuth.microsoft("i", "s", "https://x/y").autorizar().url()
                        .startsWith("https://login.microsoftonline.com"));
        Check.that("GitHub también",
                OAuth.github("i", "s", "https://x/y").autorizar().url()
                        .startsWith("https://github.com/login/oauth/authorize"));
        Check.that("los alcances se pueden cambiar",
                OAuth.google("i", "s", "https://x/y").alcances("openid").autorizar().url()
                        .contains("scope=openid&"));

        Check.raises("sin identificador de cliente falla", IllegalArgumentException.class,
                () -> OAuth.google("", "s", "https://x/y"));
        Check.raises("sin redirección falla", IllegalArgumentException.class,
                () -> OAuth.google("i", "s", ""));
        Check.raises("sin código no se puede canjear", IllegalArgumentException.class,
                () -> google.intercambiar("", "verificador"));
    }

    /**
     * Levanta un Google de mentira —con sus endpoints de token y de claves— y recorre el flujo
     * entero: canje del código, verificación de la firma del id_token y lectura de la identidad.
     */
    private static void googleCompleto() throws Exception {
        KeyPair par = generar();
        RSAPublicKey publica = (RSAPublicKey) par.getPublic();
        RSAPrivateKey privada = (RSAPrivateKey) par.getPrivate();

        String idCliente = "1234.apps.googleusercontent.com";
        String kid = "clave-de-prueba";

        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("kid", kid);
        jwk.put("alg", "RS256");
        jwk.put("n", URL64.encodeToString(sinSigno(publica.getModulus())));
        jwk.put("e", URL64.encodeToString(sinSigno(publica.getPublicExponent())));
        String juegoDeClaves = Json.write(Map.of("keys", List.of(jwk)));

        Server google = Cero.app().port(0).quiet().reporter(ErrorReporter.silent())
                .routes(r -> r
                        .get("/certs", ctx -> Result.raw(juegoDeClaves))
                        .post("/token", ctx -> {
                            if (!"authorization_code".equals(ctx.form("grant_type"))) {
                                return Result.status(400, "grant_type inesperado");
                            }
                            if (ctx.form("code_verifier") == null) {
                                return Result.status(400, "falta el verificador de PKCE");
                            }
                            String idToken = firmar(privada, kid, Map.of(
                                    "iss", "https://accounts.google.com",
                                    "aud", idCliente,
                                    "sub", "108123456789",
                                    "email", "andre@unap.edu.pe",
                                    "email_verified", true,
                                    "name", "Richar Andre",
                                    "picture", "https://foto/andre.jpg",
                                    "exp", Instant.now().plusSeconds(3600).getEpochSecond()));
                            return Result.json(Map.of(
                                    "access_token", "token-de-acceso",
                                    "id_token", idToken,
                                    "token_type", "Bearer"));
                        }))
                .start();

        try {
            String base = "http://127.0.0.1:" + google.port();
            OAuth.Proveedor falso = new OAuth.Proveedor("google-de-prueba",
                    base + "/auth", base + "/token", base + "/certs", base + "/userinfo",
                    "https://accounts.google.com", true);

            OAuth cliente = OAuth.de(falso, idCliente, "secreto", "https://miapp.pe/volver",
                    "openid", "email", "profile");

            OAuth.Salida salida = cliente.autorizar();
            OAuth.Identidad quien = cliente.intercambiar("codigo-de-google", salida.verificador());

            Check.equal("el canje devuelve el sujeto", quien.sujeto(), "108123456789");
            Check.equal("y el correo", quien.correo(), "andre@unap.edu.pe");
            Check.that("marcado como verificado", quien.correoVerificado());
            Check.equal("y el nombre", quien.nombre(), "Richar Andre");
            Check.equal("y la foto", quien.foto(), "https://foto/andre.jpg");
            Check.equal("conserva el token de acceso", quien.accessToken(), "token-de-acceso");
            Check.that("y el id_token", quien.idToken() != null);

            Check.equal("se convierte en Principal",
                    quien.comoPrincipal("alumno").id(), "andre@unap.edu.pe");
            Check.that("con sus roles",
                    quien.comoPrincipal("alumno").hasRole("alumno"));

            // ── ahora lo que debe rechazar ──
            String otraAudiencia = firmar(privada, kid, Map.of(
                    "iss", "https://accounts.google.com", "aud", "app-ajena",
                    "sub", "1", "exp", Instant.now().plusSeconds(600).getEpochSecond()));
            Check.raises("audiencia ajena se rechaza", OAuth.OAuthException.class,
                    () -> cliente.verificarIdToken(otraAudiencia));

            String otroEmisor = firmar(privada, kid, Map.of(
                    "iss", "https://malo.pe", "aud", idCliente,
                    "sub", "1", "exp", Instant.now().plusSeconds(600).getEpochSecond()));
            Check.raises("emisor inesperado se rechaza", OAuth.OAuthException.class,
                    () -> cliente.verificarIdToken(otroEmisor));

            String caducado = firmar(privada, kid, Map.of(
                    "iss", "https://accounts.google.com", "aud", idCliente,
                    "sub", "1", "exp", Instant.now().minusSeconds(60).getEpochSecond()));
            Check.raises("token caducado se rechaza", OAuth.OAuthException.class,
                    () -> cliente.verificarIdToken(caducado));

            KeyPair intruso = generar();
            String firmadoPorOtro = firmar((RSAPrivateKey) intruso.getPrivate(), kid, Map.of(
                    "iss", "https://accounts.google.com", "aud", idCliente,
                    "sub", "1", "exp", Instant.now().plusSeconds(600).getEpochSecond()));
            Check.raises("firma de otra clave se rechaza", OAuth.OAuthException.class,
                    () -> cliente.verificarIdToken(firmadoPorOtro));

            String sinAlgoritmo = URL64.encodeToString(
                    Json.write(Map.of("alg", "none", "kid", kid)).getBytes(StandardCharsets.UTF_8))
                    + "." + URL64.encodeToString(Json.write(Map.of("aud", idCliente,
                    "iss", "https://accounts.google.com", "sub", "1")).getBytes(StandardCharsets.UTF_8))
                    + ".";
            Check.raises("alg:none se rechaza", OAuth.OAuthException.class,
                    () -> cliente.verificarIdToken(sinAlgoritmo));

            Check.raises("un token sin tres partes se rechaza", OAuth.OAuthException.class,
                    () -> cliente.verificarIdToken("solo.dos"));

            String manipulado = quien.idToken().substring(0, quien.idToken().lastIndexOf('.') + 1)
                    + URL64.encodeToString("firma-inventada".getBytes(StandardCharsets.UTF_8));
            Check.raises("firma manipulada se rechaza", OAuth.OAuthException.class,
                    () -> cliente.verificarIdToken(manipulado));

            estadoEnSesion(cliente);
            clavesEnCache(cliente, quien.idToken());
        } finally {
            google.stop();
        }
    }

    /**
     * El retorno del proveedor tiene que ser el de esta sesión. Sin comprobar el estado, un
     * tercero manda su propio código y la víctima acaba dentro de la cuenta de otro.
     */
    private static void estadoEnSesion(OAuth cliente) throws Exception {
        Check.group("OAuth · el estado ata el retorno a la sesión");

        Server app = Cero.app().port(0).quiet().reporter(ErrorReporter.silent())
                .routes(r -> r
                        .get("/entrar", ctx -> Result.raw(cliente.autorizar(ctx).estado()))
                        .get("/volver", ctx -> {
                            try {
                                return Result.raw("ok:" + cliente.intercambiar(ctx).sujeto());
                            } catch (OAuth.OAuthException rechazado) {
                                return Result.status(400, rechazado.getMessage());
                            }
                        }))
                .start();
        try {
            String base = "http://127.0.0.1:" + app.port();
            HttpClient conCookies = conGalletas();

            String estado = pedir(conCookies, base + "/entrar").body();
            Check.that("el paso 1 devuelve un estado", !estado.isBlank());

            HttpResponse<String> ajeno = pedir(conCookies,
                    base + "/volver?code=codigo-de-google&state=inventado");
            Check.equal("un estado que no es el guardado se rechaza", ajeno.statusCode(), 400);
            Check.that("y se dice por qué", ajeno.body().contains("estado no coincide"));

            String otroEstado = pedir(conCookies, base + "/entrar").body();
            Check.equal("sin estado tampoco pasa",
                    pedir(conCookies, base + "/volver?code=c").statusCode(), 400);

            String bueno = pedir(conCookies, base + "/entrar").body();
            Check.that("cada intento trae un estado distinto", !bueno.equals(otroEstado));

            HttpResponse<String> correcto = pedir(conCookies,
                    base + "/volver?code=codigo-de-google&state=" + bueno);
            Check.equal("con el estado de la sesión, el canje sale", correcto.statusCode(), 200);
            Check.equal("y llega la identidad", correcto.body(), "ok:108123456789");

            HttpResponse<String> repetido = pedir(conCookies,
                    base + "/volver?code=codigo-de-google&state=" + bueno);
            Check.equal("repetir el mismo retorno ya no vale", repetido.statusCode(), 400);
            Check.that("porque el estado era de un solo uso",
                    repetido.body().contains("no hay un intercambio"));

            // Otra sesión, con el estado robado de la primera: la pareja vive en la sesión.
            String suyo = pedir(conGalletas(), base + "/entrar").body();
            Check.equal("el estado de otra sesión no sirve en esta",
                    pedir(conCookies, base + "/volver?code=codigo-de-google&state=" + suyo)
                            .statusCode(), 400);
        } finally {
            app.stop();
        }
    }

    /** El juego de claves se guarda: cien entradas no son cien viajes al proveedor. */
    private static void clavesEnCache(OAuth cliente, String idToken) {
        Check.group("OAuth · claves del proveedor");

        boolean todas = true;
        for (int i = 0; i < 100; i++) {
            todas &= "108123456789".equals(cliente.verificarIdToken(idToken).sujeto());
        }
        Check.that("cien verificaciones seguidas salen bien", todas);
    }

    private static HttpClient conGalletas() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .cookieHandler(new CookieManager())
                .build();
    }

    private static HttpResponse<String> pedir(HttpClient cliente, String url) throws Exception {
        return cliente.send(HttpRequest.newBuilder(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static KeyPair generar() throws Exception {
        KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
        generador.initialize(2048);
        return generador.generateKeyPair();
    }

    private static String firmar(RSAPrivateKey privada, String kid, Map<String, Object> cuerpo) {
        try {
            String cabecera = URL64.encodeToString(
                    Json.write(Map.of("alg", "RS256", "typ", "JWT", "kid", kid))
                            .getBytes(StandardCharsets.UTF_8));
            String carga = URL64.encodeToString(Json.write(cuerpo).getBytes(StandardCharsets.UTF_8));
            Signature firma = Signature.getInstance("SHA256withRSA");
            firma.initSign(privada);
            firma.update((cabecera + "." + carga).getBytes(StandardCharsets.US_ASCII));
            return cabecera + "." + carga + "." + URL64.encodeToString(firma.sign());
        } catch (Exception fallo) {
            throw new IllegalStateException("no se pudo firmar el token de prueba", fallo);
        }
    }

    private static byte[] sinSigno(BigInteger valor) {
        byte[] bytes = valor.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] recortado = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, recortado, 0, recortado.length);
            return recortado;
        }
        return bytes;
    }
}
