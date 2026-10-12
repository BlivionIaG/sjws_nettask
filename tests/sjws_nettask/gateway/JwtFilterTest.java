package sjws_nettask.gateway;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Request;

class JwtFilterTest {

    private static final long NOW = 1_700_000_000L;

    @Test
    void hmacAcceptsACurrentTokenAndRejectsTheRest() throws Exception {
        var secret = bytes(32, 1);
        var other = bytes(48, 2);
        var clock = new AtomicLong(NOW);
        var filter = JwtFilter.create(GatewayTestSupport.spec("jwt", Map.of(
                "alg", "HS256, HS384",
                "hmac-keys", "a:" + Encoding.base64Url(secret) + ", b:" + Encoding.base64Url(other),
                "issuer", "me",
                "audience", "api",
                "leeway", "10")), clock::get);
        var good = token("HS256", "a", "{\"iss\":\"me\",\"aud\":[\"api\",\"other\"],\"exp\":" + NOW + ",\"nbf\":" + NOW + "}",
                secret, "HmacSHA256");
        assertEquals(200, call(filter, good).status());

        clock.set(NOW + 11);
        assertEquals(401, call(filter, good).status());
        clock.set(NOW);

        var early = token("HS256", "a", "{\"iss\":\"me\",\"aud\":\"api\",\"exp\":" + (NOW + 60) + ",\"nbf\":" + (NOW + 11) + "}",
                secret, "HmacSHA256");
        assertEquals(401, call(filter, early).status());
        clock.set(NOW + 1);
        assertEquals(200, call(filter, early).status());
        clock.set(NOW);

        var hs384 = token("HS384", "b", "{\"iss\":\"me\",\"aud\":\"api\",\"exp\":" + (NOW + 5) + "}", other, "HmacSHA384");
        assertEquals(200, call(filter, hs384).status());

        var wrongKey = token("HS256", "a", "{\"iss\":\"me\",\"aud\":\"api\",\"exp\":" + (NOW + 5) + "}", other, "HmacSHA256");
        assertEquals(401, call(filter, wrongKey).status());
        var badIss = token("HS256", "a", "{\"iss\":\"other\",\"aud\":\"api\",\"exp\":" + (NOW + 5) + "}", secret, "HmacSHA256");
        assertEquals(401, call(filter, badIss).status());
        var noExp = token("HS256", "a", "{\"iss\":\"me\",\"aud\":\"api\"}", secret, "HmacSHA256");
        assertEquals(401, call(filter, noExp).status());
        assertEquals(401, call(filter, "not-a-jwt").status());
        assertEquals(401, call(filter, null).status());
        var head = call(filter, "Bearer");
        assertEquals(401, head.status());
        assertTrue(head.headOnly());
        assertEquals("Bearer", call(filter, "not-a-jwt").headers().get("www-authenticate"));
    }

    @Test
    void noneCritRemoteAndTypAreRejected() throws Exception {
        var secret = bytes(64, 3);
        var filter = JwtFilter.create(GatewayTestSupport.spec("jwt", Map.of(
                "alg", "HS512",
                "hmac-keys", Encoding.base64Url(secret),
                "require-exp", "false",
                "prefix", "")));
        var ok = token("HS512", null, "{\"sub\":\"a\"}", secret, "HmacSHA512");
        assertEquals(200, GatewayTestSupport.call(filter, GatewayTestSupport.request("GET", "/",
                Map.of("authorization", List.of(ok)))).status());
        assertEquals(401, call(filter, header("none", null, "{\"sub\":\"a\"}") + ".e30").status());
        assertEquals(401, call(filter, signedHeader(secret, "HmacSHA512",
                "{\"alg\":\"HS512\",\"crit\":[\"exp\"]}", "{\"sub\":\"a\"}")).status());
        assertEquals(401, call(filter, signedHeader(secret, "HmacSHA512",
                "{\"alg\":\"HS512\",\"jku\":\"https://evil.example/jwks\"}", "{\"sub\":\"a\"}")).status());
        assertEquals(401, call(filter, signedHeader(secret, "HmacSHA512",
                "{\"alg\":\"HS512\",\"typ\":\"at+jwt\"}", "{\"sub\":\"a\"}")).status());
        assertEquals(401, call(filter, signedHeader(secret, "HmacSHA512",
                "{\"alg\":\"HS512\",\"alg\":\"HS512\"}", "{\"sub\":\"a\"}")).status());
    }

    @Test
    void twoKeysWithoutAKidDoNotGuess() throws Exception {
        var a = bytes(32, 4);
        var b = bytes(32, 5);
        var filter = JwtFilter.create(GatewayTestSupport.spec("jwt", Map.of(
                "alg", "HS256",
                "hmac-keys", "a:" + Encoding.base64Url(a) + ",b:" + Encoding.base64Url(b),
                "require-exp", "false")));
        var token = token("HS256", null, "{}", a, "HmacSHA256");
        assertEquals(401, call(filter, token).status());
        var named = token("HS256", "b", "{}", b, "HmacSHA256");
        assertEquals(200, call(filter, named).status());
    }

    @Test
    void rsaAndEcdsa() throws Exception {
        var rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        var rsaKeys = rsa.generateKeyPair();
        var p256 = ec("secp256r1");
        var p384 = ec("secp384r1");
        var p521 = ec("secp521r1");
        var filter = JwtFilter.create(GatewayTestSupport.spec("jwt", Map.of(
                "alg", "RS256, RS384, ES256, ES384, ES512",
                "rsa-keys", "rsa:" + Encoding.base64Url(rsaKeys.getPublic().getEncoded()),
                "ec-keys", "p256:" + Encoding.base64Url(p256.getPublic().getEncoded())
                        + ",p384:" + Encoding.base64Url(p384.getPublic().getEncoded())
                        + ",p521:" + Encoding.base64Url(p521.getPublic().getEncoded()),
                "issuer", "me")), clock());
        assertEquals(200, call(filter, signed(rsaKeys, "RS256", "SHA256withRSA", "rsa", payload())).status());
        assertEquals(200, call(filter, signed(rsaKeys, "RS384", "SHA384withRSA", "rsa", payload())).status());
        assertEquals(200, call(filter, signed(p256, "ES256", "SHA256withECDSA", "p256", payload())).status());
        assertEquals(200, call(filter, signed(p384, "ES384", "SHA384withECDSA", "p384", payload())).status());
        assertEquals(200, call(filter, signed(p521, "ES512", "SHA512withECDSA", "p521", payload())).status());
        var wrongCurve = Encoding.base64Url(headerJson("ES256", "p384").getBytes(StandardCharsets.UTF_8))
                + "." + Encoding.base64Url(payload().getBytes(StandardCharsets.UTF_8))
                + "." + Encoding.base64Url(new byte[64]);
        assertEquals(401, call(filter, wrongCurve).status());
        assertEquals(401, call(filter, signed(rsaKeys, "RS256", "SHA256withRSA", "missing", payload())).status());
    }

    @Test
    void ecdsaJoseRoundTripAndConfig() {
        var random = new SecureRandom();
        for (int part : new int[] { 32, 48, 66 }) {
            var jose = new byte[part * 2];
            random.nextBytes(jose);
            assertArrayEquals(jose, JwtFilter.derToJose(JwtFilter.joseToDer(jose, part), part));
        }
        assertThrows(IllegalArgumentException.class, () -> JwtFilter.joseToDer(new byte[3], 32));
        assertThrows(IllegalArgumentException.class, () -> JwtFilter.derToJose(new byte[] { 0x30, 0x00 }, 32));
        assertThrows(IllegalArgumentException.class,
                () -> JwtFilter.create(GatewayTestSupport.spec("jwt", Map.of("alg", "none"))));
        assertThrows(IllegalArgumentException.class,
                () -> JwtFilter.create(GatewayTestSupport.spec("jwt", Map.of("alg", "HS256"))));
        assertThrows(IllegalArgumentException.class,
                () -> JwtFilter.create(GatewayTestSupport.spec("jwt", Map.of(
                        "alg", "HS512", "hmac-keys", Encoding.base64Url(bytes(32, 9))))));
        assertThrows(IllegalArgumentException.class,
                () -> JwtFilter.create(GatewayTestSupport.spec("jwt", Map.of(
                        "alg", "RS256", "rsa-keys", "not-a-key"))));
        assertThrows(IllegalArgumentException.class,
                () -> JwtFilter.create(GatewayTestSupport.spec("jwt", Map.of(
                        "alg", "HS256", "hmac-keys", "a:" + Encoding.base64Url(bytes(32, 1))
                                + ",a:" + Encoding.base64Url(bytes(32, 2))))));
        assertThrows(IllegalArgumentException.class,
                () -> JwtFilter.create(GatewayTestSupport.spec("jwt", Map.of(
                        "alg", "HS256", "hmac-keys", Encoding.base64Url(bytes(16, 1))))));
        assertThrows(IllegalArgumentException.class,
                () -> JwtFilter.create(GatewayTestSupport.spec("jwt", Map.of(
                        "alg", "HS256", "hmac-keys", Encoding.base64Url(bytes(32, 1)), "leeway", "no"))));
        assertThrows(IllegalArgumentException.class,
                () -> JwtFilter.create(GatewayTestSupport.spec("jwt", Map.of(
                        "alg", "HS256", "hmac-keys", Encoding.base64Url(bytes(32, 1)), "require-exp", "maybe"))));
    }

    @Test
    void publicKeyBytesAreNotAnHmacSecret() throws Exception {
        var rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        var keys = rsa.generateKeyPair();
        var spki = keys.getPublic().getEncoded();
        var secret = bytes(32, 7);
        var p256 = ec("secp256r1");
        var ecSpki = p256.getPublic().getEncoded();
        assertThrows(IllegalArgumentException.class, () -> JwtFilter.create(GatewayTestSupport.spec("jwt", Map.of(
                "alg", "HS256,RS256",
                "hmac-keys", Encoding.base64Url(spki),
                "rsa-keys", Encoding.base64Url(spki)))));
        assertThrows(IllegalArgumentException.class, () -> JwtFilter.create(GatewayTestSupport.spec("jwt", Map.of(
                "alg", "HS256,ES256",
                "hmac-keys", Encoding.base64Url(ecSpki),
                "ec-keys", Encoding.base64Url(ecSpki)))));
        var rsOnly = JwtFilter.create(GatewayTestSupport.spec("jwt", Map.of(
                "alg", "RS256",
                "rsa-keys", "k:" + Encoding.base64Url(spki),
                "require-exp", "false")));
        var forged = token("HS256", "k", "{}", spki, "HmacSHA256");
        assertEquals(401, call(rsOnly, forged).status());
        var dual = JwtFilter.create(GatewayTestSupport.spec("jwt", Map.of(
                "alg", "HS256,RS256",
                "hmac-keys", "k:" + Encoding.base64Url(secret),
                "rsa-keys", "k:" + Encoding.base64Url(spki),
                "ec-keys", Encoding.base64Url(ecSpki),
                "require-exp", "false")));
        assertEquals(200, call(dual, token("HS256", "k", "{}", secret, "HmacSHA256")).status());
        assertEquals(200, call(dual, signed(keys, "RS256", "SHA256withRSA", "k", "{}")).status());
        assertEquals(401, call(dual, forged).status());
        assertEquals(401, call(dual, token("HS256", "k", "{}", keys.getPrivate().getEncoded(), "HmacSHA256")).status());
    }

    @Test
    void negativeExpIsRejectedAndLeewayIsInclusive() throws Exception {
        var secret = bytes(32, 8);
        var params = Map.of(
                "alg", "HS256",
                "hmac-keys", Encoding.base64Url(secret),
                "require-exp", "false",
                "leeway", "10");
        var filter = JwtFilter.create(GatewayTestSupport.spec("jwt", params), () -> NOW);
        assertEquals(401, call(filter, token("HS256", null, "{\"exp\":-1}", secret, "HmacSHA256")).status());
        assertEquals(401, call(filter, token("HS256", null, "{\"nbf\":-1}", secret, "HmacSHA256")).status());
        assertEquals(401, call(filter, token("HS256", null, "{\"exp\":true}", secret, "HmacSHA256")).status());
        var edge = token("HS256", null, "{\"exp\":" + NOW + "}", secret, "HmacSHA256");
        var atLeeway = JwtFilter.create(GatewayTestSupport.spec("jwt", params), () -> NOW + 10);
        assertEquals(200, call(atLeeway, edge).status());
        var past = JwtFilter.create(GatewayTestSupport.spec("jwt", params), () -> NOW + 11);
        assertEquals(401, call(past, edge).status());
    }

    private static java.util.function.LongSupplier clock() {
        return () -> NOW;
    }

    private static String payload() {
        return "{\"iss\":\"me\",\"exp\":" + (NOW + 30) + "}";
    }

    private static sjws_nettask.core.Response call(sjws_nettask.core.RequestFilter filter, String bearer) {
        if (bearer == null) {
            return GatewayTestSupport.call(filter, new Request("GET", "/"));
        }
        if ("Bearer".equals(bearer)) {
            return GatewayTestSupport.call(filter, new Request("HEAD", "/",
                    sjws_nettask.core.RequestHeaders.of(Map.of("authorization", List.of("Bearer")))));
        }
        return GatewayTestSupport.call(filter, GatewayTestSupport.request("GET", "/",
                Map.of("authorization", List.of(bearer.startsWith("ey") || bearer.contains(".")
                        ? "Bearer " + bearer : bearer))));
    }

    private static String token(String alg, String kid, String payload, byte[] secret, String macAlg) throws Exception {
        return signedHeader(secret, macAlg, headerJson(alg, kid), payload);
    }

    private static String signedHeader(byte[] secret, String macAlg, String headerJson, String payload) throws Exception {
        var h = Encoding.base64Url(headerJson.getBytes(StandardCharsets.UTF_8));
        var p = Encoding.base64Url(payload.getBytes(StandardCharsets.UTF_8));
        var mac = Mac.getInstance(macAlg);
        mac.init(new SecretKeySpec(secret, macAlg));
        var sig = Encoding.base64Url(mac.doFinal((h + "." + p).getBytes(StandardCharsets.US_ASCII)));
        return h + "." + p + "." + sig;
    }

    private static String signed(KeyPair pair, String alg, String jca, String kid, String payload) throws Exception {
        var h = Encoding.base64Url(headerJson(alg, kid).getBytes(StandardCharsets.UTF_8));
        var p = Encoding.base64Url(payload.getBytes(StandardCharsets.UTF_8));
        var signature = Signature.getInstance(jca);
        signature.initSign(pair.getPrivate());
        signature.update((h + "." + p).getBytes(StandardCharsets.US_ASCII));
        byte[] raw = signature.sign();
        if (alg.startsWith("ES")) {
            raw = JwtFilter.derToJose(raw, JwtFilter.partLen(alg));
        }
        return h + "." + p + "." + Encoding.base64Url(raw);
    }

    private static String header(String alg, String kid, String payload) {
        var h = Encoding.base64Url(headerJson(alg, kid).getBytes(StandardCharsets.UTF_8));
        var p = Encoding.base64Url(payload.getBytes(StandardCharsets.UTF_8));
        return h + "." + p;
    }

    private static String headerJson(String alg, String kid) {
        if (kid == null) {
            return "{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}";
        }
        return "{\"alg\":\"" + alg + "\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}";
    }

    private static KeyPair ec(String curve) throws Exception {
        var gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec(curve));
        return gen.generateKeyPair();
    }

    private static byte[] bytes(int n, int seed) {
        var out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) (seed + i * 17);
        }
        return out;
    }
}
