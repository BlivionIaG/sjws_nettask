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
package sjws_nettask.controlplane;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import sjws_nettask.config.AccessSettings;
import sjws_nettask.core.AccessRules;

/**
 * Wire form of an access policy pushed by the control plane: a versioned, scoped
 * {@link AccessSettings} with a canonical text encoding. The encoding is deterministic (same
 * document, same bytes), round-trips through {@link #decode}, and ends with a SHA-256 of the body
 * so a truncated or edited document is refused. Decoding compiles the rules, so an invalid pattern
 * or owner fails on load, never on the request path.
 * <pre>
 * sjws-access-policy 1
 * version 42
 * scope label:env=prod
 * dotfiles false
 * owner -
 * world-readable false
 * deny /private
 * rule hide:*.bak
 * sha256 &lt;hex of every byte above, newlines included&gt;
 * </pre>
 * The digest is an integrity check, not authentication: the transport (mTLS between CP and node)
 * or a signature over {@link #encode()} must authenticate the sender.
 */
public record AccessPolicyDocument(long version, Scope scope, AccessSettings settings) {

    static final String MAGIC = "sjws-access-policy 1";
    /** Upper bound on an encoded document, refused before parsing. */
    public static final int MAX_BYTES = 1 << 20;

    public AccessPolicyDocument {
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive: " + version);
        }
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(settings, "settings");
        for (var s : settings.deny()) {
            checkValue(s);
        }
        for (var s : settings.rules()) {
            checkValue(s);
        }
        if (settings.requireOwner() != null) {
            checkValue(settings.requireOwner());
            if (settings.requireOwner().equals("-")) {
                throw new IllegalArgumentException("owner '-' is reserved");
            }
        }
    }

    /** Compiles the rules. @throws IllegalArgumentException for a bad pattern or unknown owner */
    public AccessRules toRules() {
        var rules = new ArrayList<AccessRules.Rule>(this.settings.rules().size());
        for (var r : this.settings.rules()) {
            rules.add(AccessRules.Rule.parse(r));
        }
        return new AccessRules(this.settings.deny(), rules, this.settings.allowDotfiles(),
                this.settings.requireOwner(), this.settings.requireWorldReadable());
    }

    public String encode() {
        var b = new StringBuilder(128);
        b.append(MAGIC).append('\n');
        b.append("version ").append(this.version).append('\n');
        b.append("scope ").append(this.scope).append('\n');
        b.append("dotfiles ").append(this.settings.allowDotfiles()).append('\n');
        b.append("owner ").append(this.settings.requireOwner() == null ? "-" : this.settings.requireOwner()).append('\n');
        b.append("world-readable ").append(this.settings.requireWorldReadable()).append('\n');
        for (var d : this.settings.deny()) {
            b.append("deny ").append(d).append('\n');
        }
        for (var r : this.settings.rules()) {
            b.append("rule ").append(r).append('\n');
        }
        var digest = sha256(b.toString());
        b.append("sha256 ").append(digest).append('\n');
        return b.toString();
    }

    /**
     * Parses and validates (digest, field order, rule syntax, owner lookup).
     *
     * @throws IllegalArgumentException if anything is off
     */
    public static AccessPolicyDocument decode(String text) {
        if (text.length() > MAX_BYTES) {
            throw new IllegalArgumentException("policy document too large");
        }
        int tail = text.lastIndexOf("sha256 ");
        if (tail < 0 || !text.endsWith("\n") || (tail > 0 && text.charAt(tail - 1) != '\n')) {
            throw new IllegalArgumentException("policy document has no digest line");
        }
        var body = text.substring(0, tail);
        var digest = text.substring(tail + 7, text.length() - 1);
        if (!MessageDigest.isEqual(sha256(body).getBytes(StandardCharsets.US_ASCII),
                digest.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("policy document digest mismatch");
        }
        var lines = body.split("\n", -1); // last element is "" after the final newline
        int i = 0;
        expect(lines, i++, MAGIC);
        long version;
        try {
            version = Long.parseLong(field(lines, i++, "version"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad version");
        }
        var scope = Scope.parse(field(lines, i++, "scope"));
        boolean dot = bool(field(lines, i++, "dotfiles"));
        var owner = field(lines, i++, "owner");
        boolean world = bool(field(lines, i++, "world-readable"));
        var deny = new ArrayList<String>();
        var rules = new ArrayList<String>();
        for (; i < lines.length - 1 && lines[i].startsWith("deny "); i++) {
            deny.add(lines[i].substring(5));
        }
        for (; i < lines.length - 1 && lines[i].startsWith("rule "); i++) {
            rules.add(lines[i].substring(5));
        }
        if (i != lines.length - 1) {
            throw new IllegalArgumentException("unexpected line " + (i + 1) + " in policy document");
        }
        var doc = new AccessPolicyDocument(version, scope,
                new AccessSettings(deny, rules, dot, owner.equals("-") ? null : owner, world));
        doc.toRules(); // validate now
        if (!doc.encode().equals(text)) {
            throw new IllegalArgumentException("policy document is not in canonical form");
        }
        return doc;
    }

    private static void checkValue(String s) {
        if (s.isEmpty() || !s.strip().equals(s)) {
            throw new IllegalArgumentException("empty or padded value in policy: '" + s + "'");
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) {
                throw new IllegalArgumentException("control character in policy value");
            }
        }
    }

    private static void expect(String[] lines, int i, String want) {
        if (i >= lines.length || !lines[i].equals(want)) {
            throw new IllegalArgumentException("expected '" + want + "' on line " + (i + 1));
        }
    }

    private static String field(String[] lines, int i, String name) {
        if (i >= lines.length || !lines[i].startsWith(name + " ")) {
            throw new IllegalArgumentException("expected '" + name + "' on line " + (i + 1));
        }
        return lines[i].substring(name.length() + 1);
    }

    private static boolean bool(String s) {
        return switch (s) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("expected true or false, got '" + s + "'");
        };
    }

    static String sha256(String s) {
        return HexFormat.of().formatHex(sha256(s.getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JDK ships SHA-256
        }
    }

    /** Helper for callers holding raw lists. */
    public static AccessPolicyDocument of(long version, Scope scope, List<String> deny, List<String> rules) {
        return new AccessPolicyDocument(version, scope, new AccessSettings(deny, rules, false, null, false));
    }
}
