/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package sjws_nettask.gateway;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import sjws_nettask.core.MiddlewareSpec;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestFilter;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;

/**
 * JWT bearer check. HMAC (HS256/384/512), RSA (RS256/384/512) and ECDSA (ES256/384/512).
 * {@code exp}, {@code nbf}, {@code iss} and {@code aud} are checked after the signature.
 * Keys come from the filter params ({@code hmac-keys}, {@code rsa-keys}, {@code ec-keys}), each
 * a comma-separated list of {@code kid:base64}. There is no remote JWKS, {@code jku}, {@code jwk}
 * or {@code x5u}. {@code alg=none} is rejected even if listed. A bad token is 401; it does not
 * become a 500.
 * <p>
 * RSA and EC material is an X.509 SubjectPublicKeyInfo, base64 or base64url. HMAC material is
 * the raw secret, base64, at least as long as the hash. An HMAC secret that equals a configured
 * public-key encoding is rejected. A present {@code exp} or {@code nbf} that is not a
 * non-negative integer is rejected. Rotation is a new compiled filter: the key set is fixed for
 * the life of this instance.
 */
public final class JwtFilter implements RequestFilter {

    private static final int MAX_TOKEN = 16 * 1024;

    private final String header;
    private final String prefix;
    private final Map<String, Map<String, SecretKeySpec>> hmacSpecs;
    private final Map<String, SecretKeySpec> soleHmac;
    private final Map<String, PublicKey> rsa;
    private final Map<String, ECPublicKey> ec;
    private final String[] algs;
    private final String issuer;
    private final String audience;
    private final long leeway;
    private final boolean requireExp;
    private final LongSupplier clock;

    private JwtFilter(String header, String prefix, Map<String, byte[]> hmac, Map<String, PublicKey> rsa,
            Map<String, ECPublicKey> ec, String[] algs, String issuer, String audience, long leeway,
            boolean requireExp, LongSupplier clock) {
        this.header = header;
        this.prefix = prefix;
        this.hmacSpecs = hmacSpecs(hmac, algs);
        this.soleHmac = this.hmacSpecs.size() == 1 ? this.hmacSpecs.values().iterator().next() : null;
        this.rsa = rsa;
        this.ec = ec;
        this.algs = algs;
        this.issuer = issuer;
        this.audience = audience;
        this.leeway = leeway;
        this.requireExp = requireExp;
        this.clock = clock;
    }

    public static RequestFilter create(MiddlewareSpec spec) {
        return create(spec, () -> System.currentTimeMillis() / 1000L);
    }

    static RequestFilter create(MiddlewareSpec spec, LongSupplier clock) {
        var algs = split(spec.option("alg"), "alg");
        for (var alg : algs) {
            if (family(alg) == 0 || "none".equals(alg)) {
                throw new IllegalArgumentException("jwt alg is not supported: " + alg);
            }
        }
        var hmac = hmacKeys(spec.option("hmac-keys"));
        var rsa = publicKeys(spec.option("rsa-keys"), "RSA");
        var ec = ecKeys(spec.option("ec-keys"));
        rejectSharedMaterial(hmac, rsa, ec);
        for (var alg : algs) {
            if (!hasKey(alg, hmac, rsa, ec)) {
                throw new IllegalArgumentException("jwt alg " + alg + " has no key");
            }
        }
        var header = spec.option("header");
        if (header == null || header.isBlank()) {
            header = "authorization";
        }
        header = header.trim().toLowerCase(Locale.ROOT);
        var prefix = spec.option("prefix");
        if (prefix == null) {
            prefix = "Bearer ";
        }
        var issuer = blankToNull(spec.option("issuer"));
        var audience = blankToNull(spec.option("audience"));
        long leeway = 0;
        var leewayText = spec.option("leeway");
        if (leewayText != null) {
            leeway = parseLong(leewayText, "leeway");
        }
        boolean requireExp = true;
        var requireText = spec.option("require-exp");
        if (requireText != null) {
            var flag = requireText.trim();
            if ("false".equals(flag)) {
                requireExp = false;
            } else if (!"true".equals(flag)) {
                throw new IllegalArgumentException("require-exp must be true or false");
            }
        }
        return new JwtFilter(header, prefix, hmac, rsa, ec, algs, issuer, audience, leeway, requireExp, clock);
    }

    @Override
    public Response filter(Request request, Chain next) {
        if (!allow(request)) {
            var response = StaticFileHandler.error(401, request.headOnly());
            response.headers().set("www-authenticate", "Bearer");
            return response;
        }
        return next.proceed(request);
    }

    private boolean allow(Request request) {
        try {
            var token = token(request);
            if (token == null) {
                return false;
            }
            int dot1 = token.indexOf('.');
            int dot2 = dot1 < 0 ? -1 : token.indexOf('.', dot1 + 1);
            if (dot1 <= 0 || dot2 <= dot1 + 1 || dot2 >= token.length() - 1 || token.indexOf('.', dot2 + 1) >= 0) {
                return false;
            }
            var headerJson = MiniJson.object(utf8(Encoding.base64(token.substring(0, dot1))));
            var alg = text(headerJson, "alg");
            if (alg == null || !listed(alg) || headerJson.containsKey("crit") || remoteKey(headerJson)) {
                return false;
            }
            var typ = text(headerJson, "typ");
            if (typ != null && !"JWT".equals(typ)) {
                return false;
            }
            var kid = text(headerJson, "kid");
            if (kid == null) {
                kid = "";
            }
            byte[] sig = Encoding.base64(token.substring(dot2 + 1));
            byte[] input = token.substring(0, dot2).getBytes(StandardCharsets.US_ASCII);
            if (!verify(alg, kid, input, sig)) {
                return false;
            }
            var payload = MiniJson.object(utf8(Encoding.base64(token.substring(dot1 + 1, dot2))));
            return claims(payload);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return false;
        }
    }

    private boolean claims(Map<String, Object> payload) {
        long now = this.clock.getAsLong();
        if (payload.containsKey("exp")) {
            var exp = number(payload.get("exp"));
            if (exp == null || expired(now, exp, this.leeway)) {
                return false;
            }
        } else if (this.requireExp) {
            return false;
        }
        if (payload.containsKey("nbf")) {
            var nbf = number(payload.get("nbf"));
            if (nbf == null || notYet(now, nbf, this.leeway)) {
                return false;
            }
        }
        if (this.issuer != null && !this.issuer.equals(payload.get("iss"))) {
            return false;
        }
        if (this.audience != null && !audience(payload.get("aud"), this.audience)) {
            return false;
        }
        return true;
    }

    private String token(Request request) {
        var values = request.headers().values(this.header);
        if (values.isEmpty()) {
            return null;
        }
        var value = values.get(0);
        if (value.length() > MAX_TOKEN) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 127) {
                return null;
            }
        }
        if (this.prefix.isEmpty()) {
            return value.isEmpty() ? null : value;
        }
        if (!value.startsWith(this.prefix) || value.length() == this.prefix.length()) {
            return null;
        }
        return value.substring(this.prefix.length());
    }

    private boolean listed(String alg) {
        for (var allowed : this.algs) {
            if (allowed.equals(alg)) {
                return true;
            }
        }
        return false;
    }

    private boolean verify(String alg, String kid, byte[] input, byte[] sig) throws GeneralSecurityException {
        int kind = family(alg);
        if (kind == 1) {
            var key = hmacSpec(kid, alg);
            if (key == null || sig.length != hashLen(alg)) {
                return false;
            }
            var mac = mac(alg);
            mac.init(key);
            return MessageDigest.isEqual(mac.doFinal(input), sig);
        }
        if (kind == 2) {
            var key = select(this.rsa, kid);
            if (key == null) {
                return false;
            }
            var verifier = signature(alg);
            verifier.initVerify(key);
            verifier.update(input);
            return verifier.verify(sig);
        }
        if (kind == 3) {
            var key = select(this.ec, kid);
            if (key == null || key.getParams().getCurve().getField().getFieldSize() != curveBits(alg)) {
                return false;
            }
            var verifier = signature(alg);
            verifier.initVerify(key);
            verifier.update(input);
            return verifier.verify(joseToDer(sig, partLen(alg)));
        }
        return false;
    }

    private SecretKeySpec hmacSpec(String kid, String alg) {
        Map<String, SecretKeySpec> byAlg;
        if (kid.isEmpty()) {
            byAlg = this.soleHmac;
        } else {
            byAlg = this.hmacSpecs.get(kid);
        }
        return byAlg == null ? null : byAlg.get(alg);
    }

    private static Map<String, Map<String, SecretKeySpec>> hmacSpecs(Map<String, byte[]> hmac, String[] algs) {
        var byKid = new LinkedHashMap<String, Map<String, SecretKeySpec>>();
        for (var entry : hmac.entrySet()) {
            var byAlg = new LinkedHashMap<String, SecretKeySpec>();
            for (var alg : algs) {
                if (family(alg) == 1 && entry.getValue().length >= hashLen(alg)) {
                    byAlg.put(alg, new SecretKeySpec(entry.getValue(), jcaMac(alg)));
                }
            }
            byKid.put(entry.getKey(), Map.copyOf(byAlg));
        }
        return Map.copyOf(byKid);
    }

    private static String jcaMac(String alg) {
        return switch (alg) {
            case "HS256" -> "HmacSHA256";
            case "HS384" -> "HmacSHA384";
            case "HS512" -> "HmacSHA512";
            default -> throw new IllegalArgumentException(alg);
        };
    }

    private static <T> T select(Map<String, T> keys, String kid) {
        if (keys.isEmpty()) {
            return null;
        }
        if (kid.isEmpty() && keys.size() == 1) {
            return keys.values().iterator().next();
        }
        return keys.get(kid);
    }

    private static boolean remoteKey(Map<String, Object> header) {
        return header.containsKey("jku") || header.containsKey("jwk") || header.containsKey("x5u")
                || header.containsKey("x5c");
    }

    private static boolean audience(Object claim, String expected) {
        if (claim instanceof String s) {
            return expected.equals(s);
        }
        if (claim instanceof List<?> list) {
            for (var item : list) {
                if (expected.equals(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Long number(Object value) {
        if (value instanceof Long n) {
            return n < 0 ? null : n;
        }
        return null;
    }

    private static boolean expired(long now, long exp, long leeway) {
        try {
            return now > Math.addExact(exp, leeway);
        } catch (ArithmeticException e) {
            return true;
        }
    }

    private static boolean notYet(long now, long nbf, long leeway) {
        try {
            return now < Math.subtractExact(nbf, leeway);
        } catch (ArithmeticException e) {
            return true;
        }
    }

    private static String text(Map<String, Object> json, String key) {
        var value = json.get(key);
        return value instanceof String s ? s : null;
    }

    private static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** 1 HMAC, 2 RSA, 3 ECDSA, 0 unknown. */
    private static int family(String alg) {
        return switch (alg) {
            case "HS256", "HS384", "HS512" -> 1;
            case "RS256", "RS384", "RS512" -> 2;
            case "ES256", "ES384", "ES512" -> 3;
            default -> 0;
        };
    }

    private static int hashLen(String alg) {
        return switch (alg) {
            case "HS256" -> 32;
            case "HS384" -> 48;
            case "HS512" -> 64;
            default -> 0;
        };
    }

    static int partLen(String alg) {
        return switch (alg) {
            case "ES256" -> 32;
            case "ES384" -> 48;
            case "ES512" -> 66;
            default -> 0;
        };
    }

    private static int curveBits(String alg) {
        return switch (alg) {
            case "ES256" -> 256;
            case "ES384" -> 384;
            case "ES512" -> 521;
            default -> 0;
        };
    }

    /** The public-key bytes must not also be an HMAC secret (algorithm confusion). */
    private static void rejectSharedMaterial(Map<String, byte[]> hmac, Map<String, PublicKey> rsa,
            Map<String, ECPublicKey> ec) {
        for (var secret : hmac.values()) {
            for (var key : rsa.values()) {
                if (MessageDigest.isEqual(secret, key.getEncoded())) {
                    throw new IllegalArgumentException("hmac key must not be a public key encoding");
                }
            }
            for (var key : ec.values()) {
                if (MessageDigest.isEqual(secret, key.getEncoded())) {
                    throw new IllegalArgumentException("hmac key must not be a public key encoding");
                }
            }
        }
    }

    private static boolean hasKey(String alg, Map<String, byte[]> hmac, Map<String, PublicKey> rsa,
            Map<String, ECPublicKey> ec) {
        return switch (family(alg)) {
            case 1 -> hmac.values().stream().anyMatch(k -> k.length >= hashLen(alg));
            case 2 -> !rsa.isEmpty();
            case 3 -> ec.values().stream().anyMatch(k -> k.getParams().getCurve().getField().getFieldSize() == curveBits(alg));
            default -> false;
        };
    }

    private static Map<String, byte[]> hmacKeys(String raw) {
        var map = new LinkedHashMap<String, byte[]>();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        for (var entry : split(raw, "hmac-keys")) {
            var parsed = kidAndMaterial(entry);
            var bytes = Encoding.base64(parsed[1]);
            if (bytes.length < 32) {
                throw new IllegalArgumentException("hmac key is shorter than 32 bytes");
            }
            if (map.putIfAbsent(parsed[0], bytes) != null) {
                throw new IllegalArgumentException("duplicate hmac kid");
            }
        }
        return Map.copyOf(map);
    }

    private static Map<String, PublicKey> publicKeys(String raw, String algorithm) {
        var map = new LinkedHashMap<String, PublicKey>();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        for (var entry : split(raw, algorithm.toLowerCase(Locale.ROOT) + "-keys")) {
            var parsed = kidAndMaterial(entry);
            PublicKey key;
            try {
                var spec = new X509EncodedKeySpec(Encoding.base64(parsed[1]));
                key = KeyFactory.getInstance(algorithm).generatePublic(spec);
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                throw new IllegalArgumentException(algorithm + " key is not a SubjectPublicKeyInfo");
            }
            if (key instanceof RSAPublicKey rsa && rsa.getModulus().bitLength() < 2048) {
                throw new IllegalArgumentException("RSA key is shorter than 2048 bits");
            }
            if (map.putIfAbsent(parsed[0], key) != null) {
                throw new IllegalArgumentException("duplicate " + algorithm + " kid");
            }
        }
        return Map.copyOf(map);
    }

    private static Map<String, ECPublicKey> ecKeys(String raw) {
        var map = new LinkedHashMap<String, ECPublicKey>();
        for (var entry : publicKeys(raw, "EC").entrySet()) {
            if (!(entry.getValue() instanceof ECPublicKey ec)) {
                throw new IllegalArgumentException("EC key is not an elliptic curve key");
            }
            map.put(entry.getKey(), ec);
        }
        return Map.copyOf(map);
    }

    private static String[] kidAndMaterial(String entry) {
        int colon = entry.indexOf(':');
        String kid = colon < 0 ? "" : entry.substring(0, colon).trim();
        String material = colon < 0 ? entry : entry.substring(colon + 1).trim();
        if (material.isEmpty()) {
            throw new IllegalArgumentException("key material is required");
        }
        if (kid.indexOf(',') >= 0) {
            throw new IllegalArgumentException("kid must not contain a comma");
        }
        return new String[] { kid, material };
    }

    private static String[] split(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        var parts = raw.split(",", -1);
        var out = new ArrayList<String>(parts.length);
        for (var part : parts) {
            var trimmed = part.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException(name + " has an empty entry");
            }
            out.add(trimmed);
        }
        return out.toArray(String[]::new);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static long parseLong(String raw, String name) {
        var text = raw.trim();
        if (text.isEmpty() || text.charAt(0) == '+' || text.charAt(0) == '-') {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }

    private static Mac mac(String alg) {
        return switch (alg) {
            case "HS256" -> HS256.get();
            case "HS384" -> HS384.get();
            case "HS512" -> HS512.get();
            default -> throw new IllegalArgumentException(alg);
        };
    }

    private static Signature signature(String alg) throws GeneralSecurityException {
        var local = switch (alg) {
            case "RS256" -> RS256;
            case "RS384" -> RS384;
            case "RS512" -> RS512;
            case "ES256" -> ES256;
            case "ES384" -> ES384;
            case "ES512" -> ES512;
            default -> throw new IllegalArgumentException(alg);
        };
        return local.get();
    }

    /** JOSE R||S to DER. {@code part} is the coordinate width (32, 48 or 66). */
    static byte[] joseToDer(byte[] jose, int part) {
        if (part <= 0 || jose.length != part * 2) {
            throw new IllegalArgumentException("bad ecdsa signature");
        }
        byte[] r = derInt(jose, 0, part);
        byte[] s = derInt(jose, part, part);
        int body = 2 + r.length + 2 + s.length;
        var out = new byte[1 + lenBytes(body) + body];
        int i = 0;
        out[i++] = 0x30;
        i = putLen(out, i, body);
        out[i++] = 0x02;
        i = putLen(out, i, r.length);
        System.arraycopy(r, 0, out, i, r.length);
        i += r.length;
        out[i++] = 0x02;
        i = putLen(out, i, s.length);
        System.arraycopy(s, 0, out, i, s.length);
        return out;
    }

    /** Inverse of {@link #joseToDer}, used by tests that start from a JCA signature. */
    static byte[] derToJose(byte[] der, int part) {
        if (der.length < 8 || der[0] != 0x30) {
            throw new IllegalArgumentException("bad ecdsa signature");
        }
        int[] at = { 1 };
        int seq = readLen(der, at);
        int end = at[0] + seq;
        if (end != der.length) {
            throw new IllegalArgumentException("bad ecdsa signature");
        }
        byte[] r = readInt(der, at);
        byte[] s = readInt(der, at);
        if (at[0] != end) {
            throw new IllegalArgumentException("bad ecdsa signature");
        }
        var out = new byte[part * 2];
        place(out, 0, r, part);
        place(out, part, s, part);
        return out;
    }

    private static byte[] derInt(byte[] src, int off, int len) {
        int i = off;
        int end = off + len;
        while (i < end - 1 && src[i] == 0) {
            i++;
        }
        boolean pad = (src[i] & 0x80) != 0;
        var out = new byte[end - i + (pad ? 1 : 0)];
        if (pad) {
            out[0] = 0;
        }
        System.arraycopy(src, i, out, pad ? 1 : 0, end - i);
        return out;
    }

    private static void place(byte[] dest, int destOff, byte[] mag, int part) {
        int i = 0;
        if (mag.length > 0 && mag[0] == 0) {
            i = 1;
        }
        int n = mag.length - i;
        if (n > part) {
            throw new IllegalArgumentException("bad ecdsa signature");
        }
        System.arraycopy(mag, i, dest, destOff + (part - n), n);
    }

    private static byte[] readInt(byte[] der, int[] at) {
        if (at[0] >= der.length || der[at[0]] != 0x02) {
            throw new IllegalArgumentException("bad ecdsa signature");
        }
        at[0]++;
        int n = readLen(der, at);
        if (n <= 0 || at[0] + n > der.length) {
            throw new IllegalArgumentException("bad ecdsa signature");
        }
        var out = new byte[n];
        System.arraycopy(der, at[0], out, 0, n);
        at[0] += n;
        return out;
    }

    private static int readLen(byte[] der, int[] at) {
        if (at[0] >= der.length) {
            throw new IllegalArgumentException("bad ecdsa signature");
        }
        int b = der[at[0]++] & 0xff;
        if (b < 0x80) {
            return b;
        }
        int n = b & 0x7f;
        if (n == 0 || n > 2 || at[0] + n > der.length) {
            throw new IllegalArgumentException("bad ecdsa signature");
        }
        int v = 0;
        for (int i = 0; i < n; i++) {
            v = (v << 8) | (der[at[0]++] & 0xff);
        }
        if (v < 128) {
            throw new IllegalArgumentException("bad ecdsa signature");
        }
        return v;
    }

    private static int lenBytes(int len) {
        if (len < 128) {
            return 1;
        }
        return len < 256 ? 2 : 3;
    }

    private static int putLen(byte[] out, int i, int len) {
        if (len < 128) {
            out[i++] = (byte) len;
            return i;
        }
        if (len < 256) {
            out[i++] = (byte) 0x81;
            out[i++] = (byte) len;
            return i;
        }
        out[i++] = (byte) 0x82;
        out[i++] = (byte) (len >> 8);
        out[i++] = (byte) len;
        return i;
    }

    private static ThreadLocal<Mac> macLocal(String algorithm) {
        return ThreadLocal.withInitial(() -> {
            try {
                return Mac.getInstance(algorithm);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static ThreadLocal<Signature> signLocal(String algorithm) {
        return ThreadLocal.withInitial(() -> {
            try {
                return Signature.getInstance(algorithm);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static final ThreadLocal<Mac> HS256 = macLocal("HmacSHA256");
    private static final ThreadLocal<Mac> HS384 = macLocal("HmacSHA384");
    private static final ThreadLocal<Mac> HS512 = macLocal("HmacSHA512");
    private static final ThreadLocal<Signature> RS256 = signLocal("SHA256withRSA");
    private static final ThreadLocal<Signature> RS384 = signLocal("SHA384withRSA");
    private static final ThreadLocal<Signature> RS512 = signLocal("SHA512withRSA");
    private static final ThreadLocal<Signature> ES256 = signLocal("SHA256withECDSA");
    private static final ThreadLocal<Signature> ES384 = signLocal("SHA384withECDSA");
    private static final ThreadLocal<Signature> ES512 = signLocal("SHA512withECDSA");
}
