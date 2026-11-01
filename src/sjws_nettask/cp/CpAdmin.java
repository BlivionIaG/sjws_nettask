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
package sjws_nettask.cp;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/** Role checks in front of {@link CpStore}. The HTTP adapter calls {@link #handle}. */
public final class CpAdmin {

    private final CpStore store;
    private final long nowMillis;
    private final long ttlMillis;

    /**
     * Test-only permission overlay. When non-null, consulted after the built-in table so a test can
     * authorize a route that has no handler. Always null in production; mutate only via
     * {@link #setTestPermission}.
     */
    private static volatile BiFunction<String, String, sjws_nettask.controlplane.Permission> testPermission;

    /** Test-only: install or clear the permission overlay. */
    static void setTestPermission(BiFunction<String, String, sjws_nettask.controlplane.Permission> hook) {
        testPermission = hook;
    }

    public CpAdmin(CpStore store, long nowMillis, long ttlMillis) {
        this.store = store;
        this.nowMillis = nowMillis;
        this.ttlMillis = ttlMillis;
    }

    public CpStore.Outcome handle(sjws_nettask.controlplane.Rbac.Credential credential, String method, String path,
            String query, String body) {
        return handle(credential, method, path, query, body, null);
    }

    /**
     * {@code idempotencyKey} is the {@code Idempotency-Key} header. There is no bearer token.
     * A repeated key returns the first outcome and does not append again.
     */
    public CpStore.Outcome handle(sjws_nettask.controlplane.Rbac.Credential credential, String method, String path,
            String query, String body, String idempotencyKey) {
        var route = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
        var permission = permission(method, route);
        if (permission == null) {
            boolean known = knownRoute(route);
            return CpStore.Outcome.error(known ? 405 : 404, known ? "method" : "not found");
        }
        var decision = store.authorize(credential, permission, route);
        if (!decision.allowed()) {
            var status = decision.operator() == null ? 401 : 403;
            return CpStore.Outcome.error(status, decision.reason());
        }
        if ("GET".equals(method) && "/cp/v1/health".equals(route)) {
            return CpStore.Outcome.ok(store.healthJson(), 0);
        }
        if ("GET".equals(method) && "/cp/v1/config".equals(route)) {
            return CpStore.Outcome.ok(store.configJson(), 0);
        }
        if ("GET".equals(method) && "/cp/v1/config/diff".equals(route)) {
            return store.diff(queryLong(query, "from"), queryLong(query, "to"), false);
        }
        if ("GET".equals(method) && route.startsWith("/cp/v1/config/")) {
            try {
                return store.configVersion(Long.parseLong(route.substring("/cp/v1/config/".length())));
            } catch (NumberFormatException e) {
                return CpStore.Outcome.error(404, "version");
            }
        }
        if ("GET".equals(method) && "/cp/v1/nodes".equals(route)) {
            return CpStore.Outcome.ok(store.nodesJson(), 0);
        }
        if ("GET".equals(method) && "/cp/v1/rollout".equals(route)) {
            return CpStore.Outcome.ok(store.rolloutJson(), 0);
        }
        if ("GET".equals(method) && "/cp/v1/audit".equals(route)) {
            try {
                int limit = (int) queryLong(query, "limit");
                return CpStore.Outcome.ok(store.auditJson(queryLong(query, "after"), limit), 0);
            } catch (java.io.IOException e) {
                return CpStore.Outcome.error(503, "audit");
            }
        }
        if ("POST".equals(method) && "/cp/v1/config:validate".equals(route)) {
            return store.validate(body == null ? "" : body);
        }
        if ("POST".equals(method) && "/cp/v1/config".equals(route)) {
            return store.propose(body == null ? "" : body, idempotencyKey);
        }
        if ("POST".equals(method) && "/cp/v1/rollout".equals(route)) {
            return store.rollout(selectors(body), idempotencyKey);
        }
        if ("POST".equals(method) && "/cp/v1/rollout/advance".equals(route)) {
            return store.advance(idempotencyKey);
        }
        if ("POST".equals(method) && "/cp/v1/rollback".equals(route)) {
            return store.rollback(jsonLong(body, "toVersion"), nowMillis, ttlMillis, idempotencyKey);
        }
        if ("POST".equals(method) && route.startsWith("/cp/v1/nodes/") && route.endsWith("/revoke")) {
            var id = route.substring("/cp/v1/nodes/".length(), route.length() - "/revoke".length());
            return store.revoke(id, idempotencyKey);
        }
        if ("POST".equals(method) && "/cp/v1/activate".equals(route)) {
            return store.activate(nowMillis, ttlMillis, idempotencyKey);
        }
        if ("POST".equals(method) && "/cp/v1/rbac".equals(route)) {
            var san = jsonString(body, "san");
            var name = jsonString(body, "role");
            if (san == null || name == null) {
                return CpStore.Outcome.error(400, "rbac");
            }
            return store.grant(san, parseRole(name));
        }
        // Authorized but no handler: fail closed. Grant is only reached from /cp/v1/rbac above.
        return CpStore.Outcome.error(404, "not found");
    }

    private static sjws_nettask.controlplane.Permission permission(String method, String route) {
        if ("GET".equals(method) && ("/cp/v1/health".equals(route) || "/cp/v1/config".equals(route)
                || "/cp/v1/config/diff".equals(route) || "/cp/v1/nodes".equals(route)
                || "/cp/v1/rollout".equals(route) || "/cp/v1/audit".equals(route)
                || versionPath(route))) {
            return sjws_nettask.controlplane.Permission.READ;
        }
        if ("POST".equals(method) && ("/cp/v1/config".equals(route) || "/cp/v1/config:validate".equals(route))) {
            return sjws_nettask.controlplane.Permission.EDIT_ROUTES;
        }
        if ("POST".equals(method) && ("/cp/v1/rollout".equals(route) || "/cp/v1/rollout/advance".equals(route)
                || "/cp/v1/activate".equals(route))) {
            return sjws_nettask.controlplane.Permission.ROLLOUT;
        }
        if ("POST".equals(method) && "/cp/v1/rollback".equals(route)) {
            return sjws_nettask.controlplane.Permission.ROLLBACK;
        }
        if ("POST".equals(method) && route.startsWith("/cp/v1/nodes/") && route.endsWith("/revoke")) {
            return sjws_nettask.controlplane.Permission.MANAGE_OPERATORS;
        }
        if ("POST".equals(method) && "/cp/v1/rbac".equals(route)) {
            return sjws_nettask.controlplane.Permission.MANAGE_OPERATORS;
        }
        var hook = testPermission;
        if (hook != null) {
            var extra = hook.apply(method, route);
            if (extra != null) {
                return extra;
            }
        }
        return null;
    }

    /** True when {@code route} is a known admin path for at least one method. */
    static boolean knownRoute(String route) {
        if ("/cp/v1/health".equals(route) || "/cp/v1/config".equals(route) || "/cp/v1/config/diff".equals(route)
                || "/cp/v1/nodes".equals(route) || "/cp/v1/rollout".equals(route) || "/cp/v1/audit".equals(route)
                || "/cp/v1/config:validate".equals(route) || "/cp/v1/rollback".equals(route)
                || "/cp/v1/rollout/advance".equals(route) || "/cp/v1/activate".equals(route)
                || "/cp/v1/rbac".equals(route)) {
            return true;
        }
        if (route.startsWith("/cp/v1/nodes/") && route.endsWith("/revoke")) {
            return true;
        }
        return versionPath(route);
    }

    private static boolean versionPath(String route) {
        if (!route.startsWith("/cp/v1/config/")) {
            return false;
        }
        var tail = route.substring("/cp/v1/config/".length());
        if (tail.isEmpty() || "diff".equals(tail)) {
            return false;
        }
        for (int i = 0; i < tail.length(); i++) {
            if (!Character.isDigit(tail.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static sjws_nettask.controlplane.Role parseRole(String name) {
        return switch (name) {
            case "viewer" -> sjws_nettask.controlplane.Role.VIEWER;
            case "operator" -> sjws_nettask.controlplane.Role.OPERATOR;
            case "admin" -> sjws_nettask.controlplane.Role.ADMIN;
            default -> throw new IllegalArgumentException("role");
        };
    }

    static long queryLong(String query, String key) {
        if (query == null) {
            return -1L;
        }
        for (var part : query.split("&")) {
            var bits = part.split("=", 2);
            if (bits.length == 2 && bits[0].equals(key)) {
                try {
                    return Long.parseLong(bits[1]);
                } catch (NumberFormatException e) {
                    return -1L;
                }
            }
        }
        return -1L;
    }

    static long jsonLong(String json, String key) {
        if (json == null) {
            return -1L;
        }
        var marker = "\"" + key + "\"";
        int at = json.indexOf(marker);
        if (at < 0) {
            return -1L;
        }
        int colon = json.indexOf(':', at + marker.length());
        if (colon < 0) {
            return -1L;
        }
        int i = colon + 1;
        while (i < json.length() && json.charAt(i) == ' ') {
            i++;
        }
        int end = i;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end == i) {
            return -1L;
        }
        return Long.parseLong(json.substring(i, end));
    }

    static String jsonString(String json, String key) {
        if (json == null) {
            return null;
        }
        try {
            return LastGoodFiles.readString(json, key);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    static List<String> selectors(String body) {
        var out = new ArrayList<String>();
        if (body == null) {
            return out;
        }
        int at = body.indexOf('[');
        int end = body.indexOf(']');
        if (at < 0 || end < at) {
            return out;
        }
        for (var part : body.substring(at + 1, end).split(",")) {
            var trimmed = part.trim();
            if (trimmed.length() >= 2 && trimmed.charAt(0) == '"') {
                out.add(trimmed.substring(1, trimmed.length() - 1));
            }
        }
        return out;
    }
}
