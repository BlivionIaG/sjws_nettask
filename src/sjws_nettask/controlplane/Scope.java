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

import java.util.Map;
import java.util.Objects;

/**
 * Where a distributed policy applies: every node, nodes carrying one label, or one route.
 * Encoded as {@code *}, {@code label:KEY=VALUE} or {@code route:NAME}.
 */
public record Scope(Kind kind, String key, String value) {

    public enum Kind { ALL, LABEL, ROUTE }

    public static final Scope ALL = new Scope(Kind.ALL, "", "");

    public Scope {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        switch (kind) {
            case ALL -> {
                if (!key.isEmpty() || !value.isEmpty()) {
                    throw new IllegalArgumentException("scope * takes no key or value");
                }
            }
            case LABEL -> {
                if (!key.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,62}") || !value.matches("[A-Za-z0-9._-]{0,63}")) {
                    throw new IllegalArgumentException("bad label scope '" + key + "=" + value + "'");
                }
            }
            case ROUTE -> {
                if (!value.isEmpty() || !key.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
                    throw new IllegalArgumentException("bad route scope '" + key + "'");
                }
            }
        }
    }

    public static Scope label(String key, String value) {
        return new Scope(Kind.LABEL, key, value);
    }

    public static Scope route(String name) {
        return new Scope(Kind.ROUTE, name, "");
    }

    /** @throws IllegalArgumentException if malformed */
    public static Scope parse(String s) {
        if (s.equals("*")) {
            return ALL;
        }
        if (s.startsWith("label:")) {
            int eq = s.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException("label scope needs KEY=VALUE: '" + s + "'");
            }
            return label(s.substring(6, eq), s.substring(eq + 1));
        }
        if (s.startsWith("route:")) {
            return route(s.substring(6));
        }
        throw new IllegalArgumentException("scope must be *, label:KEY=VALUE or route:NAME: '" + s + "'");
    }

    /** @return true if a node with these labels should install this policy as its default */
    public boolean appliesToNode(Map<String, String> labels) {
        return switch (this.kind) {
            case ALL -> true;
            case LABEL -> this.value.equals(labels.get(this.key));
            case ROUTE -> false;
        };
    }

    @Override
    public String toString() {
        return switch (this.kind) {
            case ALL -> "*";
            case LABEL -> "label:" + this.key + "=" + this.value;
            case ROUTE -> "route:" + this.key;
        };
    }
}
