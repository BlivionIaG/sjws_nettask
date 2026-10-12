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
package sjws_nettask.admin.cp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code k=v,k2!=v2} (all terms must hold); empty or {@code *} matches every node. A missing label
 * never equals a value and always differs from one.
 */
public record LabelSelector(List<Term> terms) {

    public record Term(String key, String value, boolean equal) {
    }

    public static final LabelSelector ALL = new LabelSelector(List.of());

    public LabelSelector {
        terms = List.copyOf(terms);
    }

    /** @throws IllegalArgumentException on a malformed term */
    public static LabelSelector parse(String text) {
        var t = text == null ? "" : text.trim();
        if (t.isEmpty() || t.equals("*")) {
            return ALL;
        }
        var terms = new ArrayList<Term>();
        for (var part : t.split(",")) {
            var p = part.trim();
            int ne = p.indexOf("!=");
            int eq = p.indexOf('=');
            boolean equal = ne < 0;
            int at = equal ? eq : ne;
            if (at <= 0 || at == p.length() - (equal ? 1 : 2)) {
                throw new IllegalArgumentException("bad selector term '" + p + "' (want key=value or key!=value)");
            }
            terms.add(new Term(p.substring(0, at).trim(), p.substring(at + (equal ? 1 : 2)).trim(), equal));
        }
        return new LabelSelector(terms);
    }

    public boolean matches(Map<String, String> labels) {
        for (var term : this.terms) {
            if (Objects.equals(labels.get(term.key()), term.value()) != term.equal()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        if (this.terms.isEmpty()) {
            return "*";
        }
        var sb = new StringBuilder();
        for (var term : this.terms) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(term.key()).append(term.equal() ? "=" : "!=").append(term.value());
        }
        return sb.toString();
    }
}
