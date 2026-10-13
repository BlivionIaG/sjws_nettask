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
package sjws_nettask.service;

/**
 * What {@code --status} shows for the control plane. The watch client and the voter publish an
 * implementation through {@link NodeEnrollment#attach}. Nothing on the HTTP request path calls it.
 * <p>
 * Until that client exists, the process reports {@code pending} and config version {@code 0}.
 * Drain stays {@link sjws_nettask.SjwsServer#drain()}; this interface has no stop method.
 */
public interface ControlPlaneBinding {

    /**
     * One of {@code pending}, {@code connecting}, {@code connected}, {@code disconnected},
     * {@code leader}, {@code follower}. Any other value is stored as {@code disconnected}.
     */
    String connectionState();

    /** Applied desired-config version, or {@code 0} when none has been applied. Negative values are stored as 0. */
    long configVersion();
}
