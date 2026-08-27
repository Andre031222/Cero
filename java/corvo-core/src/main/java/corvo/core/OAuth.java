package corvo.core;

import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Autenticación con un proveedor OAuth 2.0 / OpenID Connect, con PKCE obligatorio.
 * Trae Google, Microsoft y GitHub configurados; cualquier otro se declara a mano.
 *
 * <pre>
 *   OAuth google = OAuth.google(idCliente, secreto, "https://miapp.pe/entrar/google");
 *
 *   // 1 — mandar al usuario al proveedor
 *   OAuth.Salida salida = google.autorizar();
 *   ctx.session().set("oauth", salida.verificador() + "|" + salida.estado());
 *   return Result.redirect(salida.url());
 *
 *   // 2 — el proveedor vuelve con ?code=…&amp;state=…
 *   OAuth.Identidad quien = google.intercambiar(ctx.query("code"), verificador);
 *   // quien.correo(), quien.nombre(), quien.sujeto()
 * </pre>
 */
public final class OAuth {

    private static final SecureRandom ALEATORIO = new SecureRandom();
    private static final Base64.Encoder URL64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL64_DEC = Base64.getUrlDecoder();

    private final Proveedor proveedor;
    private final String idCliente;
    private final String secreto;
    private final String redireccion;
    private final List<String> alcances;

    private OAuth(Proveedor proveedor, String idCliente, String secreto,
                  String redireccion, List<String> alcances) {
        this.proveedor = proveedor;
        this.idCliente = requerido(idCliente, "idCliente");
        this.secreto = requerido(secreto, "secreto");
        this.redireccion = requerido(redireccion, "redireccion");
        this.alcances = alcances;
    }

    public static OAuth google(String idCliente, String secreto, String redireccion) {
        return new OAuth(Proveedor.GOOGLE, idCliente, secreto, redireccion,
                List.of("openid", "email", "profile"));
    }

    public static OAuth microsoft(String idCliente, String secreto, String redireccion) {
        return new OAuth(Proveedor.MICROSOFT, idCliente, secreto, redireccion,
                List.of("openid", "email", "profile"));
    }

    public static OAuth github(String idCliente, String secreto, String redireccion) {
        return new OAuth(Proveedor.GITHUB, idCliente, secreto, redireccion, List.of("read:user", "user:email"));
    }

    public static OAuth de(Proveedor proveedor, String idCliente, String secreto,
                           String redireccion, String... alcances) {
        return new OAuth(proveedor, idCliente, secreto, redireccion, List.of(alcances));
    }

    public OAuth alcances(String... valores) {
        return new OAuth(proveedor, idCliente, secreto, redireccion, List.of(valores));
    }

    /** Paso 1: la URL a la que mandar al usuario, con su verificador y estado. */
    public Salida autorizar() {
        String verificador = azar(64);
        String estado = azar(32);
        String reto = URL64.encodeToString(sha256(verificador.getBytes(StandardCharsets.US_ASCII)));

        StringBuilder url = new StringBuilder(proveedor.autorizacion())
                .append(proveedor.autorizacion().indexOf('?') >= 0 ? '&' : '?')
                .append("client_id=").append(escapar(idCliente))
                .append("&redirect_uri=").append(escapar(redireccion))
                .append("&response_type=code")
                .append("&scope=").append(escapar(String.join(" ", alcances)))
                .append("&state=").append(escapar(estado))
                .append("&code_challenge=").append(escapar(reto))
                .append("&code_challenge_method=S256");

        if (proveedor.pideConsentimiento()) {
            url.append("&access_type=offline&prompt=consent");
        }
        return new Salida(url.toString(), verificador, estado);
    }

    /** Paso 2: canjea el código por los tokens y devuelve quién es el usuario. */
    public Identidad intercambiar(String codigo, String verificador) {
        if (codigo == null || codigo.isBlank()) {
            throw new IllegalArgumentException("falta el código de autorización");
        }
        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put("client_id", idCliente);
        campos.put("client_secret", secreto);
        campos.put("code", codigo);
        campos.put("code_verifier", verificador);
        campos.put("grant_type", "authorization_code");
        campos.put("redirect_uri", redireccion);

        Http.Respuesta respuesta = Http.to(proveedor.token())
                .header("Accept", "application/json")
                .retry(2)
                .postForm(campos);

        if (!respuesta.ok()) {
            throw new OAuthException("el proveedor rechazó el canje: " + respuesta.cuerpo());
        }
        Map<?, ?> tokens = (Map<?, ?>) respuesta.json();
        String acceso = texto(tokens.get("access_token"));
        String identidad = texto(tokens.get("id_token"));

        if (identidad != null && proveedor.emisor() != null) {
            return desdeIdToken(identidad, acceso);
        }
        if (acceso == null) {
            throw new OAuthException("la respuesta no trae access_token");
        }
        return desdePerfil(acceso);
    }

    /**
     * Verifica la firma y las reivindicaciones de un id_token contra las claves del proveedor.
     * Comprueba algoritmo, audiencia, emisor y caducidad; rechaza {@code alg: none}.
     */
    public Identidad verificarIdToken(String idToken) {
        String[] partes = idToken.split("\\.");
        if (partes.length != 3) {
            throw new OAuthException("el id_token no tiene tres partes");
        }
        Map<?, ?> cabecera = (Map<?, ?>) Json.read(new String(URL64_DEC.decode(partes[0]), StandardCharsets.UTF_8));
        Map<?, ?> cuerpo = (Map<?, ?>) Json.read(new String(URL64_DEC.decode(partes[1]), StandardCharsets.UTF_8));

        String algoritmo = texto(cabecera.get("alg"));
        if (!"RS256".equals(algoritmo)) {
            throw new OAuthException("algoritmo no admitido en el id_token: " + algoritmo);
        }
        if (!firmaValida(partes, texto(cabecera.get("kid")))) {
            throw new OAuthException("la firma del id_token no es válida");
        }
        comprobarReivindicaciones(cuerpo);
        return identidadDe(cuerpo);
    }

    private Identidad desdeIdToken(String idToken, String acceso) {
        Identidad quien = verificarIdToken(idToken);
        return new Identidad(quien.sujeto(), quien.correo(), quien.correoVerificado(),
                quien.nombre(), quien.foto(), acceso, idToken);
    }

    private Identidad desdePerfil(String acceso) {
        Http.Respuesta perfil = Http.to(proveedor.perfil())
                .bearer(acceso)
                .header("Accept", "application/json")
                .get();
        if (!perfil.ok()) {
            throw new OAuthException("no se pudo leer el perfil: " + perfil.cuerpo());
        }
        Map<?, ?> datos = (Map<?, ?>) perfil.json();
        return new Identidad(
                texto(datos.get("id") != null ? datos.get("id") : datos.get("sub")),
                texto(datos.get("email")),
                Boolean.TRUE.equals(datos.get("email_verified")),
                texto(datos.get("name") != null ? datos.get("name") : datos.get("login")),
                texto(datos.get("picture") != null ? datos.get("picture") : datos.get("avatar_url")),
                acceso, null);
    }

    private void comprobarReivindicaciones(Map<?, ?> cuerpo) {
        String audiencia = texto(cuerpo.get("aud"));
        if (!idCliente.equals(audiencia)) {
            throw new OAuthException("el id_token es para otra aplicación: " + audiencia);
        }
        String emisor = texto(cuerpo.get("iss"));
        if (emisor == null || !proveedor.emisorValido(emisor)) {
            throw new OAuthException("emisor inesperado: " + emisor);
        }
        Object caduca = cuerpo.get("exp");
        if (caduca instanceof Number cuando
                && Instant.ofEpochSecond(cuando.longValue()).isBefore(Instant.now())) {
            throw new OAuthException("el id_token está caducado");
        }
    }

    private static Identidad identidadDe(Map<?, ?> cuerpo) {
        return new Identidad(
                texto(cuerpo.get("sub")),
                texto(cuerpo.get("email")),
                Boolean.TRUE.equals(cuerpo.get("email_verified")),
                texto(cuerpo.get("name")),
                texto(cuerpo.get("picture")),
                null, null);
    }

    private boolean firmaValida(String[] partes, String kid) {
        Http.Respuesta claves = Http.to(proveedor.claves()).retry(2).get();
        if (!claves.ok()) {
            throw new OAuthException("no se pudieron leer las claves del proveedor");
        }
        Map<?, ?> juego = (Map<?, ?>) claves.json();
        Object lista = juego.get("keys");
        if (!(lista instanceof List<?> items)) {
            throw new OAuthException("el juego de claves no tiene el formato esperado");
        }
        byte[] firmado = (partes[0] + "." + partes[1]).getBytes(StandardCharsets.US_ASCII);
        byte[] firma = URL64_DEC.decode(partes[2]);

        for (Object item : items) {
            if (!(item instanceof Map<?, ?> clave)) {
                continue;
            }
            if (kid != null && !kid.equals(texto(clave.get("kid")))) {
                continue;
            }
            if (!"RSA".equals(texto(clave.get("kty")))) {
                continue;
            }
            try {
                BigInteger modulo = new BigInteger(1, URL64_DEC.decode(texto(clave.get("n"))));
                BigInteger exponente = new BigInteger(1, URL64_DEC.decode(texto(clave.get("e"))));
                var publica = KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(modulo, exponente));
                Signature verificador = Signature.getInstance("SHA256withRSA");
                verificador.initVerify(publica);
                verificador.update(firmado);
                if (verificador.verify(firma)) {
                    return true;
                }
            } catch (Exception claveInservible) {
                continue;
            }
        }
        return false;
    }

    private static String azar(int bytes) {
        byte[] datos = new byte[bytes];
        ALEATORIO.nextBytes(datos);
        return URL64.encodeToString(datos);
    }

    private static byte[] sha256(byte[] datos) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(datos);
        } catch (Exception imposible) {
            throw new IllegalStateException("falta SHA-256 en la JVM", imposible);
        }
    }

    private static String escapar(String texto) {
        return URLEncoder.encode(texto, StandardCharsets.UTF_8);
    }

    private static String texto(Object valor) {
        return valor == null ? null : String.valueOf(valor);
    }

    private static String requerido(String valor, String nombre) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("falta " + nombre);
        }
        return valor;
    }

    /** Quién es el usuario, según el proveedor. */
    public record Identidad(String sujeto, String correo, boolean correoVerificado,
                            String nombre, String foto, String accessToken, String idToken) {

        public Principal comoPrincipal(String... roles) {
            return Principal.of(correo != null ? correo : sujeto, roles);
        }
    }

    /** Lo que hay que guardar en sesión antes de mandar al usuario al proveedor. */
    public record Salida(String url, String verificador, String estado) {
    }

    public record Proveedor(String nombre, String autorizacion, String token,
                            String claves, String perfil, String emisor,
                            boolean pideConsentimiento) {

        public static final Proveedor GOOGLE = new Proveedor("google",
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                "https://www.googleapis.com/oauth2/v3/certs",
                "https://www.googleapis.com/oauth2/v3/userinfo",
                "https://accounts.google.com",
                true);

        public static final Proveedor MICROSOFT = new Proveedor("microsoft",
                "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
                "https://login.microsoftonline.com/common/oauth2/v2.0/token",
                "https://login.microsoftonline.com/common/discovery/v2.0/keys",
                "https://graph.microsoft.com/oidc/userinfo",
                "https://login.microsoftonline.com",
                false);

        public static final Proveedor GITHUB = new Proveedor("github",
                "https://github.com/login/oauth/authorize",
                "https://github.com/login/oauth/access_token",
                null,
                "https://api.github.com/user",
                null,
                false);

        boolean emisorValido(String recibido) {
            if (emisor == null) {
                return false;
            }
            // Google emite indistintamente con y sin esquema
            return recibido.equals(emisor)
                    || recibido.equals(emisor.replace("https://", ""))
                    || recibido.startsWith(emisor);
        }
    }

    public static final class OAuthException extends RuntimeException {
        public OAuthException(String mensaje) {
            super(mensaje);
        }
    }
}
