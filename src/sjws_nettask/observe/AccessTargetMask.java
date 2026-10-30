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
package sjws_nettask.observe;

import java.util.LinkedHashSet;
import java.util.List;
import sjws_nettask.core.FilterDocument;
import sjws_nettask.core.MiddlewareSpec;

/**
 * Masks configured api-key query parameter values in a request-target before it is written to the
 * access log. Names are collected at config load or filter-document apply. With no names the hot
 * path returns the input string unchanged (same reference, no allocation or parsing).
 * <p>
 * Name matching follows {@link sjws_nettask.gateway.ApiKeyFilter#query}: case-sensitive, not
 * percent-decoded, value ends at {@code &} or {@code #}.
 */
public final class AccessTargetMask {

    /** Shared empty array used when no api-key query option is configured. */
    public static final String[] NONE = new String[0];

    static final String MASK = "***";

    private AccessTargetMask() {
    }

    /**
     * Distinct non-blank {@code query} options from {@code api-key} middleware entries, in first-seen
     * order. Empty input yields {@link #NONE}.
     */
    public static String[] namesFromMiddleware(List<MiddlewareSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return NONE;
        }
        LinkedHashSet<String> names = null;
        for (var spec : specs) {
            if (!"api-key".equals(spec.name())) {
                continue;
            }
            var query = spec.option("query");
            if (query == null) {
                continue;
            }
            query = query.trim();
            if (query.isEmpty()) {
                continue;
            }
            if (names == null) {
                names = new LinkedHashSet<>();
            }
            names.add(query);
        }
        return names == null || names.isEmpty() ? NONE : names.toArray(String[]::new);
    }

    /**
     * Distinct non-blank {@code query} params from {@code api-key} filter entries, in first-seen
     * order. Empty input yields {@link #NONE}.
     */
    public static String[] namesFromDocument(FilterDocument document) {
        if (document == null || document.filters().isEmpty()) {
            return NONE;
        }
        LinkedHashSet<String> names = null;
        for (var entry : document.filters()) {
            if (!"api-key".equals(entry.type())) {
                continue;
            }
            var query = entry.params().get("query");
            if (query == null) {
                continue;
            }
            query = query.trim();
            if (query.isEmpty()) {
                continue;
            }
            if (names == null) {
                names = new LinkedHashSet<>();
            }
            names.add(query);
        }
        return names == null || names.isEmpty() ? NONE : names.toArray(String[]::new);
    }

    /**
     * Replaces the value of each configured query name with {@link #MASK}. When {@code names} is
     * null or empty, or the target has no query string, returns {@code target} unchanged.
     */
    public static String mask(String target, String[] names) {
        if (names == null || names.length == 0 || target == null) {
            return target;
        }
        int q = target.indexOf('?');
        if (q < 0) {
            return target;
        }
        StringBuilder sb = null;
        int n = target.length();
        int i = q + 1;
        int copyFrom = 0;
        while (i < n) {
            int hash = target.indexOf('#', i);
            int amp = target.indexOf('&', i);
            int pairEnd = n;
            boolean stop = false;
            if (amp >= 0 && (hash < 0 || amp < hash)) {
                pairEnd = amp;
            } else if (hash >= 0) {
                pairEnd = hash;
                stop = true;
            } else {
                stop = true;
            }
            int eq = target.indexOf('=', i);
            if (eq >= i && eq < pairEnd && nameMatches(names, target, i, eq - i)) {
                if (sb == null) {
                    sb = new StringBuilder(target.length());
                }
                sb.append(target, copyFrom, eq + 1);
                sb.append(MASK);
                copyFrom = pairEnd;
            }
            if (stop) {
                break;
            }
            i = amp + 1;
        }
        if (sb == null) {
            return target;
        }
        sb.append(target, copyFrom, n);
        return sb.toString();
    }

    private static boolean nameMatches(String[] names, String target, int offset, int length) {
        for (var name : names) {
            if (name.length() == length && target.regionMatches(offset, name, 0, length)) {
                return true;
            }
        }
        return false;
    }
}
