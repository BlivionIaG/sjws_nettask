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

import java.net.URI;
import java.time.Duration;

/**
 * One named upstream. Maps onto {@code ProxyHandler.Settings} as
 * {@code (url, connectTimeout, responseTimeout, maxBody, proto)} where {@code proto} comes from
 * {@link Routing#forwardedProto()} ({@link ForwardedProto#AUTO} is chosen per connection).
 * {@code idleReadTimeout} and {@code preserveHost} are kept for that workstream; the prototype
 * {@code Settings} record does not carry them yet.
 *
 * @param maxBody request body cap, bytes (fits in a positive {@code int})
 */
public record Upstream(String name, URI url, Duration connectTimeout, Duration responseTimeout,
        Duration idleReadTimeout, int maxBody, boolean preserveHost) {
}
