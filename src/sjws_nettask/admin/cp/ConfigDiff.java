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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import sjws_nettask.admin.cp.CpClient.ConfigBundle;
import sjws_nettask.admin.cp.CpClient.Section;

/**
 * Per-section entry diff. Routes and filters are ordered (first match / chain order), so a pure
 * reordering is reported for them; the other sections are sets.
 */
public final class ConfigDiff {

    private ConfigDiff() {
    }

    /** @return lines {@code "- routes: x"}, {@code "+ routes: y"}, {@code "~ routes: order changed"}; empty if equal */
    public static List<String> diff(ConfigBundle base, ConfigBundle draft) {
        var out = new ArrayList<String>();
        for (var section : Section.values()) {
            var name = section.name().toLowerCase(Locale.ROOT);
            var before = base.entries(section);
            var after = draft.entries(section);
            var removed = new LinkedHashSet<>(before);
            removed.removeAll(after);
            var added = new LinkedHashSet<>(after);
            added.removeAll(before);
            removed.forEach(e -> out.add("- " + name + ": " + e));
            added.forEach(e -> out.add("+ " + name + ": " + e));
            if (removed.isEmpty() && added.isEmpty() && ordered(section) && !before.equals(after)) {
                out.add("~ " + name + ": order changed");
            }
        }
        return out;
    }

    static boolean ordered(Section section) {
        return section == Section.ROUTES || section == Section.FILTERS;
    }
}
