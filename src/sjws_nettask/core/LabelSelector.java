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
package sjws_nettask.core;

import java.util.Set;

/**
 * Node-label selector compiled into a filter snapshot. Matching runs when a document is applied,
 * not on the request path: a data-plane node either includes the filter or leaves it out.
 * <p>
 * Syntax: {@code *} (or null, blank) matches every node; otherwise a comma-separated list of
 * labels that must be present, with {@code !label} for a label that must be absent. A label is
 * {@code [A-Za-z0-9._:-]+}. The canonical form has no spaces and drops a selector that matches
 * every node.
 */
public final class LabelSelector {

    private static final LabelSelector ALL = new LabelSelector(new String[0], new String[0], null);

    private final String[] require;
    private final String[] forbid;
    private final String canonical;

    private LabelSelector(String[] require, String[] forbid, String canonical) {
        this.require = require;
        this.forbid = forbid;
        this.canonical = canonical;
    }

    /** @param raw selector text; null, blank and {@code *} match every node */
    public static LabelSelector parse(String raw) {
        if (raw == null) {
            return ALL;
        }
        var text = raw.trim();
        if (text.isEmpty() || "*".equals(text)) {
            return ALL;
        }
        var parts = text.split(",", -1);
        var require = new String[parts.length];
        var forbid = new String[parts.length];
        int nRequire = 0;
        int nForbid = 0;
        for (var part : parts) {
            var token = part.trim();
            if (token.isEmpty()) {
                throw new IllegalArgumentException("bad label selector: " + raw);
            }
            boolean neg = token.charAt(0) == '!';
            var label = neg ? token.substring(1) : token;
            if (!valid(label)) {
                throw new IllegalArgumentException("bad label selector: " + raw);
            }
            if (neg) {
                forbid[nForbid++] = label;
            } else {
                require[nRequire++] = label;
            }
        }
        require = trim(require, nRequire);
        forbid = trim(forbid, nForbid);
        return new LabelSelector(require, forbid, canonical(require, forbid));
    }

    /** @return the canonical selector, or null when every node matches */
    public String canonical() {
        return this.canonical;
    }

    /** @param nodeLabels labels on this data-plane node; null is treated as none */
    public boolean matches(Set<String> nodeLabels) {
        if (this.canonical == null) {
            return true;
        }
        var labels = nodeLabels == null ? Set.<String>of() : nodeLabels;
        for (var label : this.require) {
            if (!labels.contains(label)) {
                return false;
            }
        }
        for (var label : this.forbid) {
            if (labels.contains(label)) {
                return false;
            }
        }
        return true;
    }

    private static boolean valid(String label) {
        if (label.isEmpty()) {
            return false;
        }
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.'
                    || c == '_' || c == ':' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static String[] trim(String[] source, int n) {
        if (n == source.length) {
            return source;
        }
        var out = new String[n];
        System.arraycopy(source, 0, out, 0, n);
        return out;
    }

    private static String canonical(String[] require, String[] forbid) {
        var sb = new StringBuilder();
        for (var label : require) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(label);
        }
        for (var label : forbid) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append('!').append(label);
        }
        return sb.toString();
    }
}
