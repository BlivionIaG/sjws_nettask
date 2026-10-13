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
 * Adds handlers under path prefixes while the server is starting. {@link #contribute} runs once,
 * on the startup thread, before the listener accepts. It must not block on I/O and must not touch
 * a request.
 * <p>
 * The server finds contributors two ways, and only at startup: {@link java.util.ServiceLoader} on
 * this interface, and an explicit list (an optional module that implements this interface). With
 * none, or when every contributor mounts nothing, the terminal handler is the one the server
 * would have used anyway.
 */
@FunctionalInterface
public interface HandlerContributor {

    void contribute(HandlerMounts mounts);
}
