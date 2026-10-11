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
package sjws_nettask;

import java.time.Duration;
import java.util.function.Supplier;
import sjws_nettask.core.ResponseHeaders;

/**
 * Extra-headers supplier for HTTP/1.1 responses: {@code Alt-Svc: h3=":<port>"; ma=<seconds>}
 * once an HTTP/3 listener is actually bound, nothing otherwise. Thread-safe.
 */
public final class AltSvc implements Supplier<ResponseHeaders> {

    private volatile String value; // null = not advertised

    /** @return the header value for an HTTP/3 endpoint on {@code port} */
    public static String headerValue(int port, Duration maxAge) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        return "h3=\":" + port + "\"; ma=" + maxAge.toSeconds();
    }

    public void advertise(int port, Duration maxAge) {
        this.value = headerValue(port, maxAge);
    }

    public void withdraw() {
        this.value = null;
    }

    /** @return the advertised value, or null */
    public String current() {
        return this.value;
    }

    /** @return a fresh header set per call (callers may not share mutable headers) */
    @Override
    public ResponseHeaders get() {
        var v = this.value;
        return v == null ? ResponseHeaders.of() : ResponseHeaders.of().add("alt-svc", v);
    }
}
