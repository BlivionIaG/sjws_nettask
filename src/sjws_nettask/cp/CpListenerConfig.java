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

import java.nio.file.Path;

/**
 * <b>Security-owned placeholder.</b> Listener TLS settings for {@link WatchServer} and {@link AdminServer}
 * ({@code cp.tls.*}, control-plane-hosting.md §4/§7). Hosting only passes it through to
 * {@link CpTls#serverContext(CpListenerConfig)}; Security's listener-config PR replaces this whole file.
 */
public record CpListenerConfig(Path cert, Path key, Path ca, Path adminCa) {

    /** Minimal reading of the raw {@code cp.tls} block (paths only). Replaced by Security. */
    static CpListenerConfig fromYaml(Object tls, Path base) {
        if (!(tls instanceof java.util.Map<?, ?> map)) {
            return new CpListenerConfig(null, null, null, null);
        }
        return new CpListenerConfig(path(map.get("cert"), base), path(map.get("key"), base), path(map.get("ca"), base),
                path(map.get("adminCa"), base));
    }

    private static Path path(Object raw, Path base) {
        if (!(raw instanceof String s) || s.isBlank()) {
            return null;
        }
        var p = Path.of(s);
        return p.isAbsolute() || base == null ? p : base.resolve(p);
    }
}
