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
 * Builds a {@link RequestFilter} from one {@code middleware} entry. Registered by name with
 * {@link RequestFilters#factory}; the configuration workstream does not construct filters itself.
 */
@FunctionalInterface
public interface FilterFactory {

    /**
     * @param spec name, order, route and options from configuration (or from a programmatic
     *        {@link RequestFilters#add(MiddlewareSpec)})
     * @return a filter used for every request that matches {@code spec.route()}; never null
     * @throws IllegalArgumentException when {@code spec.options()} is not valid for this filter
     */
    RequestFilter create(MiddlewareSpec spec);
}
