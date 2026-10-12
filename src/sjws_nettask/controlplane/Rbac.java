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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.security.auth.x500.X500Principal;

/**
 * Control-plane authorization: one call for the CP API and the admin TUI before any action.
 * <ul>
 * <li>Identity: a bearer token ({@code sjws_<operator>.<secret>}, only its SHA-256 is stored) or
 * the subject of an mTLS client certificate <b>already verified</b> by the TLS layer against the
 * operator CA.</li>
 * <li>Deny by default: unknown, disabled or malformed credentials and permissions outside the
 * role are refused.</li>
 * <li>Every decision, allowed or denied, is written to the {@link AuditLog} before it is returned;
 * if the audit write fails the action is denied. {@link #complete} records the outcome.</li>
 * </ul>
 * The operator table is replaced atomically; changes go through {@link #putOperator} and
 * {@link #removeOperator}, which require {@link Permission#MANAGE_OPERATORS} and refuse to leave
 * no enabled admin.
 */
public final class Rbac {

    /** How a caller proved who it is. */
    public sealed interface Credential permits Token, ClientCert {
    }

    /** {@code Authorization: Bearer <token>} value. */
    public record Token(String token) implements Credential {
        @Override
        public String toString() {
            return "Token[redacted]";
        }
    }

    /** URI SAN or subject DN of a client certificate the TLS layer verified. */
    public record ClientCert(String subject) implements Credential {
    }

    /** @param operator null when the caller could not be identified */
    public record Decision(boolean allowed, String operator, Permission permission, String target, long version,
            String reason) {
    }

    private static final String TOKEN_PREFIX = "sjws_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AtomicReference<Map<String, Operator>> operators;
    private final AuditLog audit;

    public Rbac(List<Operator> initial, AuditLog audit) {
        this.audit = Objects.requireNonNull(audit, "audit");
        this.operators = new AtomicReference<>(index(initial));
    }

    public List<Operator> operators() {
        return List.copyOf(this.operators.get().values());
    }

    /** Fresh random token for {@code operatorId}; give it to the operator once, store {@link #hashToken}. */
    public static String newToken(String operatorId) {
        var b = new byte[32];
        RANDOM.nextBytes(b);
        return TOKEN_PREFIX + operatorId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String hashToken(String token) {
        return HexFormat.of().formatHex(AccessPolicyDocument.sha256(token.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * A URI SAN such as {@code urn:sjws:admin:alice} (the identity the control-plane design uses)
     * is kept as is; anything else is a subject DN in RFC 2253 canonical form, so "CN=a, O=b" and
     * "cn=a,o=b" match.
     */
    static String canonicalSubject(String dn) {
        if (dn.startsWith("urn:")) {
            if (!dn.matches("urn:[A-Za-z0-9:._-]{1,250}")) {
                throw new IllegalArgumentException("bad URI SAN '" + dn + "'");
            }
            return dn;
        }
        try {
            return new X500Principal(dn).getName(X500Principal.CANONICAL);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad certificate subject '" + dn + "'");
        }
    }

    /** @return the enabled operator behind the credential, or null */
    public Operator authenticate(Credential c) {
        var ops = this.operators.get();
        if (c instanceof Token(String t)) {
            if (t == null || !t.startsWith(TOKEN_PREFIX)) {
                return null;
            }
            int dot = t.indexOf('.', TOKEN_PREFIX.length());
            if (dot < 0) {
                return null;
            }
            var presented = hashToken(t).getBytes(StandardCharsets.US_ASCII); // hash first: same work either way
            var op = ops.get(t.substring(TOKEN_PREFIX.length(), dot));
            if (op == null || op.tokenHash() == null || !op.enabled()) {
                return null;
            }
            return MessageDigest.isEqual(presented, op.tokenHash().getBytes(StandardCharsets.US_ASCII)) ? op : null;
        }
        if (c instanceof ClientCert(String subject)) {
            if (subject == null) {
                return null;
            }
            String canon;
            try {
                canon = canonicalSubject(subject);
            } catch (IllegalArgumentException e) {
                return null;
            }
            for (var op : ops.values()) {
                if (op.enabled() && canon.equals(op.certSubject())) {
                    return op;
                }
            }
        }
        return null;
    }

    /**
     * Authenticates, checks the permission and audits the decision.
     *
     * @param target what is acted on (route, node, version), for the audit trail
     * @param version config version the action reads or produces (0 if none)
     */
    public Decision authorize(Credential c, Permission p, String target, long version) {
        Objects.requireNonNull(p, "permission");
        var op = authenticate(c);
        Decision d;
        if (op == null) {
            d = new Decision(false, null, p, target, version, "unauthenticated");
        } else if (!op.role().allows(p)) {
            d = new Decision(false, op.id(), p, target, version, "role " + op.role() + " lacks " + p);
        } else {
            d = new Decision(true, op.id(), p, target, version, "ok");
        }
        try {
            this.audit.append(actor(d, c), p.name(), target, d.allowed() ? "allowed" : "denied: " + d.reason(),
                    version);
        } catch (IOException e) {
            return new Decision(false, d.operator(), p, target, version, "audit unavailable");
        }
        return d;
    }

    /** Records how an allowed action ended (e.g. the version a rollout produced). */
    public void complete(Decision d, boolean success, long resultVersion, String detail) throws IOException {
        if (!d.allowed()) {
            throw new IllegalArgumentException("complete() on a denied decision");
        }
        this.audit.append(d.operator(), d.permission().name(), d.target(),
                (success ? "ok" : "failed") + (detail == null ? "" : ": " + detail), resultVersion);
    }

    /** Adds or replaces an operator. @return the decision (the change is applied only if allowed) */
    public Decision putOperator(Credential actor, Operator op) {
        var d = authorize(actor, Permission.MANAGE_OPERATORS, "operator:" + op.id(), 0);
        if (!d.allowed()) {
            return d;
        }
        return change(d, m -> m.put(op.id(), op));
    }

    public Decision removeOperator(Credential actor, String id) {
        var d = authorize(actor, Permission.MANAGE_OPERATORS, "operator:" + id, 0);
        if (!d.allowed()) {
            return d;
        }
        return change(d, m -> m.remove(id));
    }

    private Decision change(Decision d, java.util.function.Consumer<Map<String, Operator>> edit) {
        while (true) {
            var cur = this.operators.get();
            var next = new LinkedHashMap<>(cur);
            edit.accept(next);
            if (next.values().stream().noneMatch(o -> o.enabled() && o.role() == Role.ADMIN)) {
                var refused = new Decision(false, d.operator(), d.permission(), d.target(), d.version(),
                        "would leave no enabled admin");
                try {
                    complete(d, false, 0, refused.reason());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return refused;
            }
            if (this.operators.compareAndSet(cur, Map.copyOf(next))) {
                try {
                    complete(d, true, 0, null);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return d;
            }
        }
    }

    private static String actor(Decision d, Credential c) {
        if (d.operator() != null) {
            return d.operator();
        }
        return c instanceof ClientCert(String s) ? "unknown-cert:" + s : "unknown-token";
    }

    private static Map<String, Operator> index(List<Operator> ops) {
        var m = new LinkedHashMap<String, Operator>();
        for (var o : ops) {
            if (m.put(o.id(), o) != null) {
                throw new IllegalArgumentException("duplicate operator " + o.id());
            }
        }
        return Map.copyOf(m);
    }
}
