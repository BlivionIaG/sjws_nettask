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

/** Role checks in front of {@link CpStore}. The HTTP adapter calls {@link #handle}. */
public final class CpAdmin {

    private final CpStore store;
    private final long nowMillis;
    private final long ttlMillis;

    public CpAdmin(CpStore store, long nowMillis, long ttlMillis) {
        this.store = store;
        this.nowMillis = nowMillis;
        this.ttlMillis = ttlMillis;
    }

    public CpStore.Outcome handle(Role role, String method, String path, String query, String body) {
        if (role == null) {
            return CpStore.Outcome.error(401, "unauthorized");
        }
        var route = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
        if ("GET".equals(method) && "/cp/v1/health".equals(route)) {
            return permit(role, Role.VIEW, store.healthJson());
        }
        if ("GET".equals(method) && "/cp/v1/config".equals(route)) {
            return permit(role, Role.VIEW, store.configJson());
        }
        if ("GET".equals(method) && "/cp/v1/config/diff".equals(route)) {
            if (!role.atLeast(Role.VIEW)) {
                return CpStore.Outcome.error(403, "forbidden");
            }
            return store.diff(queryLong(query, "from"), queryLong(query, "to"), false);
        }
        if ("GET".equals(method) && "/cp/v1/nodes".equals(route)) {
            return permit(role, Role.VIEW, store.nodesJson());
        }
        if ("POST".equals(method) && "/cp/v1/config".equals(route)) {
            if (!role.atLeast(Role.OPERATE)) {
                return CpStore.Outcome.error(403, "forbidden");
            }
            return store.propose(body == null ? "" : body);
        }
        if ("POST".equals(method) && "/cp/v1/rollout".equals(route)) {
            if (!role.atLeast(Role.OPERATE)) {
                return CpStore.Outcome.error(403, "forbidden");
            }
            return store.rollout(selectors(body));
        }
        if ("POST".equals(method) && "/cp/v1/rollout/advance".equals(route)) {
            if (!role.atLeast(Role.OPERATE)) {
                return CpStore.Outcome.error(403, "forbidden");
            }
            return store.advance();
        }
        if ("POST".equals(method) && "/cp/v1/rollback".equals(route)) {
            if (!role.atLeast(Role.OPERATE)) {
                return CpStore.Outcome.error(403, "forbidden");
            }
            return store.rollback(jsonLong(body, "toVersion"), nowMillis, ttlMillis);
        }
        if ("POST".equals(method) && route.startsWith("/cp/v1/nodes/") && route.endsWith("/revoke")) {
            if (!role.atLeast(Role.ADMIN)) {
                return CpStore.Outcome.error(403, "forbidden");
            }
            var id = route.substring("/cp/v1/nodes/".length(), route.length() - "/revoke".length());
            return store.revoke(id);
        }
        if ("POST".equals(method) && "/cp/v1/activate".equals(route)) {
            if (!role.atLeast(Role.OPERATE)) {
                return CpStore.Outcome.error(403, "forbidden");
            }
            return store.activate(nowMillis, ttlMillis);
        }
        if ("POST".equals(method) && "/cp/v1/rbac".equals(route)) {
            if (!role.atLeast(Role.ADMIN)) {
                return CpStore.Outcome.error(403, "forbidden");
            }
            var san = jsonString(body, "san");
            var name = jsonString(body, "role");
            if (san == null || name == null) {
                return CpStore.Outcome.error(400, "rbac");
            }
            return store.grant(san, Role.parse(name));
        }
        return CpStore.Outcome.error(404, "not found");
    }

    private static CpStore.Outcome permit(Role role, Role required, String body) {
        if (!role.atLeast(required)) {
            return CpStore.Outcome.error(403, "forbidden");
        }
        return CpStore.Outcome.ok(body, 0);
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
