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

/**
 * The snapshot a data-plane caller can read without a control-plane type. Transports do not use it.
 */
public interface AppliedConfig {

    /** {@link ConfigDocument#bundleVersion()}. {@code 0} when unset. */
    long version();

    /** SHA-256 of the canonical YAML, lowercase hex. */
    String canonicalHash();

    /** Control-plane epoch. {@code 0} on a local snapshot. */
    long epoch();
}
