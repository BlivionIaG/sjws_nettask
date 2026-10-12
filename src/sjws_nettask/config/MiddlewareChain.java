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
import java.util.Map;

/**
 * Reserved middleware chain. Stored on the snapshot and not applied: another workstream owns the
 * handlers.
 */
public record MiddlewareChain(List<MiddlewareChain.Step> steps) {

    public static final MiddlewareChain EMPTY = new MiddlewareChain(List.of());

    public MiddlewareChain {
        steps = List.copyOf(steps);
    }

    /** One step: a name plus a flat string parameter map. */
    public record Step(String name, Map<String, String> params) {

        public Step {
            params = Map.copyOf(params);
        }
    }
}
