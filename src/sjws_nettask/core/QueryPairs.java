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

/**
 * Shared request-target query-pair scanner for api-key acceptance and access-log masking.
 * <p>
 * Pairs are split on {@code &} only. A {@code #} does not end the scan: it only truncates the
 * current pair's value (same as {@code sjws_nettask.gateway.ApiKeyFilter#query}). Names and values
 * are not percent-decoded. Matching is case-sensitive and compares the raw name bytes. Scan is
 * linear in the target length (each {@code indexOf} advances).
 */
public final class QueryPairs {

    private QueryPairs() {
    }

    /**
     * Visits each {@code name=value} pair after the first {@code ?}. Returns true from
     * {@link Visitor#pair} to stop early. Pairs without {@code =} are skipped. Value ends at the
     * next {@code &}, a {@code #} inside the value, or the end of the string.
     */
    public static void scan(String target, Visitor visitor) {
        if (target == null || visitor == null) {
            return;
        }
        int q = target.indexOf('?');
        if (q < 0) {
            return;
        }
        int i = q + 1;
        int n = target.length();
        while (i < n) {
            int amp = target.indexOf('&', i);
            int end = amp < 0 ? n : amp;
            int eq = target.indexOf('=', i);
            if (eq >= i && eq < end) {
                int hash = target.indexOf('#', eq + 1);
                int valueEnd = end;
                if (hash >= 0 && hash < valueEnd) {
                    valueEnd = hash;
                }
                if (visitor.pair(target, i, eq, eq + 1, valueEnd)) {
                    return;
                }
            }
            if (amp < 0) {
                return;
            }
            i = amp + 1;
        }
    }

    /** Called for each {@code name=value} pair. Indices are into {@code target}. */
    @FunctionalInterface
    public interface Visitor {
        /**
         * @param target the request-target being scanned
         * @param nameStart inclusive start of the raw name
         * @param nameEnd exclusive end of the raw name (index of {@code =})
         * @param valueStart inclusive start of the raw value
         * @param valueEnd exclusive end of the raw value (before {@code &}, {@code #}, or EOS)
         * @return true to stop scanning
         */
        boolean pair(String target, int nameStart, int nameEnd, int valueStart, int valueEnd);
    }
}
