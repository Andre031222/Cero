package jxmvc.core;

public class OAuthTest {

    static int passed = 0;
    static int failed = 0;

    static void ok(String label) { passed++; System.out.println("  OK  " + label); }
    static void fail(String label, String msg) { failed++; System.out.println("  FAIL " + label + ": " + msg); }
    static void check(String label, boolean cond) { if (cond) ok(label); else fail(label, "false"); }

    public static void main(String[] args) {
        testPasswordRoundtrip();
        testPasswordFormat();
        testPasswordTampering();
        testPkceChallengeVector();
        testTokenUrlSafe();
        testAuthorizeUrl();
        testUnconfiguredFails();
        testIdTokenVerification();
        System.out.printf("OAuthTest: pass=%d fail=%d%n", passed, failed);
    }

    // ── Verificación OIDC del id_token (JWT RS256) ─────────────────────────

    interface ThrowingRunnable { void run() throws Exception; }

    static boolean throws401(ThrowingRunnable r) {
        try { r.run(); return false; }
        catch (JxException e) { return e.getStatus() == 401; }
        catch (Exception e) { return false; }
    }

    static String b64url(byte[] b) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String signJwt(java.security.PrivateKey pk, String headerJson, String payloadJson) throws Exception {
        String h = b64url(headerJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String p = b64url(payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.security.Signature s = java.security.Signature.getInstance("SHA256withRSA");
        s.initSign(pk);
        s.update((h + "." + p).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return h + "." + p + "." + b64url(s.sign());
    }

    static void testIdTokenVerification() {
        try {
            java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            java.security.KeyPair kp = g.generateKeyPair();
            java.security.PublicKey pub = kp.getPublic();
            String iss = "https://accounts.google.com";
            long now = System.currentTimeMillis() / 1000L;

            String header  = "{\"alg\":\"RS256\",\"kid\":\"test\"}";
            String payload = "{\"iss\":\"" + iss + "\",\"aud\":\"cid\",\"exp\":" + (now + 3600)
                    + ",\"sub\":\"123\",\"email\":\"a@b.com\",\"email_verified\":true,\"name\":\"Ada\"}";
            String jwt = signJwt(kp.getPrivate(), header, payload);

            java.util.Map<String, Object> claims = JxOAuth.verifyJwtClaims(jwt, pub, iss, "cid", now);
            check("id_token válido: sub",   "123".equals(String.valueOf(claims.get("sub"))));
            check("id_token válido: email", "a@b.com".equals(String.valueOf(claims.get("email"))));

            String tampered = jwt.substring(0, jwt.length() - 4) + "AAAA";
            check("firma manipulada rechazada",
                    throws401(() -> JxOAuth.verifyJwtClaims(tampered, pub, iss, "cid", now)));
            check("audience incorrecta rechazada",
                    throws401(() -> JxOAuth.verifyJwtClaims(jwt, pub, iss, "otra", now)));
            check("issuer incorrecto rechazado",
                    throws401(() -> JxOAuth.verifyJwtClaims(jwt, pub, "https://malo", "cid", now)));
            check("id_token expirado rechazado",
                    throws401(() -> JxOAuth.verifyJwtClaims(jwt, pub, iss, "cid", now + 7200)));

            String noneJwt = b64url(header.replace("RS256", "none").getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    + "." + b64url(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".";
            check("alg:none rechazado",
                    throws401(() -> JxOAuth.verifyJwtClaims(noneJwt, pub, iss, "cid", now)));

            java.security.interfaces.RSAPublicKey rpk = (java.security.interfaces.RSAPublicKey) pub;
            java.security.PublicKey rebuilt = JxOAuth.rsaKey(
                    b64url(rpk.getModulus().toByteArray()),
                    b64url(rpk.getPublicExponent().toByteArray()));
            java.util.Map<String, Object> c2 = JxOAuth.verifyJwtClaims(jwt, rebuilt, iss, "cid", now);
            check("clave RSA reconstruida desde JWK verifica", "123".equals(String.valueOf(c2.get("sub"))));
        } catch (Exception e) {
            fail("id_token verification", e.toString());
        }
    }

    // ── JxPasswords ────────────────────────────────────────────────────────

    static void testPasswordRoundtrip() {
        String hash = JxPasswords.hash("s3cr3t!");
        check("verify acepta la correcta",      JxPasswords.verify("s3cr3t!", hash));
        check("verify rechaza la incorrecta",   !JxPasswords.verify("otra", hash));
        check("hashes distintos por salt",      !JxPasswords.hash("s3cr3t!").equals(hash));
        check("verify con hash null es false",  !JxPasswords.verify("s3cr3t!", null));
        check("verify con plain null es false", !JxPasswords.verify(null, hash));
    }

    static void testPasswordFormat() {
        String hash = JxPasswords.hash("abc", 1000);
        check("prefijo pbkdf2$sha256$",  hash.startsWith("pbkdf2$sha256$"));
        check("cinco segmentos $",       hash.split("\\$").length == 5);
        check("iteraciones embebidas",   hash.split("\\$")[2].equals("1000"));
        check("verifica coste bajo",     JxPasswords.verify("abc", hash));
        check("needsRehash coste bajo",  JxPasswords.needsRehash(hash));
        check("no rehash coste default", !JxPasswords.needsRehash(JxPasswords.hash("abc")));
    }

    static void testPasswordTampering() {
        check("hash vacío no rompe",      !JxPasswords.verify("x", ""));
        check("hash basura no rompe",     !JxPasswords.verify("x", "pbkdf2$sha256$mal"));
        check("segmentos incompletos",    !JxPasswords.verify("x", "pbkdf2$sha256$1000$YWJj"));
        check("iteraciones no numéricas", !JxPasswords.verify("x", "pbkdf2$sha256$xx$YWJj$YWJj"));
    }

    // ── JxOAuth ────────────────────────────────────────────────────────────

    // Vector de prueba PKCE del RFC 7636, Apéndice B.
    static void testPkceChallengeVector() {
        String verifier  = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String expected  = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
        check("code_challenge S256 (RFC 7636)",
                expected.equals(JxOAuth.codeChallengeS256(verifier)));
    }

    static void testTokenUrlSafe() {
        String t = JxOAuth.newToken(24);
        check("token no vacío",     t != null && !t.isBlank());
        check("token URL-safe",     t.matches("[A-Za-z0-9_-]+"));
        check("tokens no repetidos", !JxOAuth.newToken(24).equals(t));
    }

    static void testAuthorizeUrl() {
        JxOAuth.Provider p = new JxOAuth.Provider(
                "https://auth.example/authorize",
                "https://auth.example/token",
                "https://auth.example/userinfo",
                "cid", "sec", "https://app.local/auth/google/callback",
                "openid email profile");
        String url = JxOAuth.of(p).authorizeUrl(p.redirectUri(), "st8", "chal");

        check("base del endpoint",   url.startsWith("https://auth.example/authorize?"));
        check("client_id",           url.contains("client_id=cid"));
        check("response_type=code",  url.contains("response_type=code"));
        check("state",               url.contains("state=st8"));
        check("code_challenge",      url.contains("code_challenge=chal"));
        check("method S256",         url.contains("code_challenge_method=S256"));
        check("redirect_uri codificado",
                url.contains("redirect_uri=https%3A%2F%2Fapp.local%2Fauth%2Fgoogle%2Fcallback"));
        check("scope codificado",    url.contains("scope=openid+email+profile"));
    }

    static void testUnconfiguredFails() {
        JxOAuth.Provider blank = new JxOAuth.Provider(
                "https://a/auth", "https://a/token", "https://a/userinfo",
                "", "", "", "openid email");
        boolean threw = false;
        try {
            JxOAuth.of(blank).authorizeUrl("", "st", "ch");
        } catch (JxException e) {
            threw = e.getStatus() == 500;
        }
        check("authorizeUrl sin credenciales lanza 500", threw);
    }
}
