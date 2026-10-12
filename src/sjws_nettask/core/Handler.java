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
 * Protocol-neutral request handler: seam between transports and application logic.
 * {@link StaticFileHandler} is the file-serving implementation. A router, a redirect, or a proxy
 * is another implementation and replaces it as the terminal handler. {@link FilterChain} is a
 * handler too: it wraps whichever terminal is installed.
 * <p>
 * Implementations must be thread-safe and should never throw; unexpected failures become a 5xx
 * {@link Response}.
 */
@FunctionalInterface
public interface Handler {

    Response handle(Request request);
}
