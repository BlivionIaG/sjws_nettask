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
package sjws_nettask.config;

import java.util.List;
import sjws_nettask.tls.TlsMaterial;

/**
 * Outcome of one reload. On failure {@code snapshot} is the snapshot that was already in use.
 *
 * @param needsRestart changes that were parsed and then left at their previous values
 * @param tls freshly loaded file certificate, or null when file TLS did not change
 */
public record ReloadResult(boolean applied, int generation, List<String> warnings, List<String> errors,
        List<String> needsRestart, ConfigSnapshot snapshot, TlsMaterial tls) {

    public ReloadResult {
        warnings = List.copyOf(warnings);
        errors = List.copyOf(errors);
        needsRestart = List.copyOf(needsRestart);
    }

    public static ReloadResult rejected(ConfigSnapshot current, List<String> errors) {
        return new ReloadResult(false, current.generation(), List.of(), errors, List.of(), current, null);
    }
}
