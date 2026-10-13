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
package sjws_nettask.routing.discovery;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide sources by service name: the hook for the control-plane watch client. The routing
 * compiler registers one {@link StaticUpstreamSource} per multi-endpoint upstream; a source
 * survives config reloads, so endpoints the control plane sent are kept when the file is reloaded.
 * A reload that changes the file's endpoints updates a source the control plane never fed.
 * <p>
 * The watch client calls {@code UpstreamRegistry.lookup(service)} and then
 * {@link UpstreamSource#update}. A service with no multi-endpoint upstream in the config has no
 * source (lookup returns null): there is nothing on the request path to feed.
 */
public final class UpstreamRegistry {

    private static final Map<String, StaticUpstreamSource> SOURCES = new ConcurrentHashMap<>();

    private UpstreamRegistry() {
    }

    /** @return the source for {@code service}, or null */
    public static UpstreamSource lookup(String service) {
        return SOURCES.get(service);
    }

    /**
     * Config compile: the existing source for this service, with the config set applied unless the
     * control plane fed it; or a new source holding {@code fromConfig}.
     */
    public static StaticUpstreamSource fromConfig(UpstreamSet fromConfig) {
        Objects.requireNonNull(fromConfig, "set");
        var existing = SOURCES.get(fromConfig.service());
        if (existing != null) {
            existing.reloadFromConfig(fromConfig);
            return existing;
        }
        var created = new StaticUpstreamSource(fromConfig);
        var raced = SOURCES.putIfAbsent(fromConfig.service(), created);
        if (raced != null) {
            raced.reloadFromConfig(fromConfig);
            return raced;
        }
        return created;
    }

    /** Tests only: forget every source. */
    static void clear() {
        SOURCES.clear();
    }
}
