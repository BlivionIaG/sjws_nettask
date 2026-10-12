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

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Protocol-neutral request. Immutable. A filter that changes the method or target passes a
 * {@link #withMethod} / {@link #withTarget} copy into {@code proceed}; those copies keep
 * {@link #peer}, {@link #secure} and {@link #headers}.
 * <p>
 * There is no body component. A later change can add a pull-style body (empty by default) without
 * moving these fields; see {@code docs/filters.md}. Do not buffer the body into a {@code byte[]}.
 *
 * @param method request method, case-sensitive ("GET", "HEAD", ...)
 * @param target origin-form request-target: raw (still percent-encoded) path plus optional "?query"
 * @param headers conditionals, negotiation fields, and the raw field map (never null;
 *        use {@link RequestHeaders#NONE})
 * @param peer client address, or null when the transport could not read one. One address per
 *        HTTP/1 connection (reused for every request on it) and one per HTTP/3 stream
 * @param secure true when the request arrived over TLS (HTTP/3 today; plain HTTP/1 is false)
 * @param protocol the version token ({@code HTTP/1.0}, {@code HTTP/1.1}, {@code HTTP/3}, ...).
 *        Null becomes {@code HTTP/3} when {@code secure} is true and {@code HTTP/1.1} otherwise,
 *        so existing constructors keep a protocol without an extra allocation
 */
public record Request(String method, String target, RequestHeaders headers, InetSocketAddress peer, boolean secure,
        String protocol) {

    public Request {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(headers, "headers");
        if (protocol == null) {
            protocol = secure ? "HTTP/3" : "HTTP/1.1";
        }
    }

    public Request(String method, String target, RequestHeaders headers, InetSocketAddress peer, boolean secure) {
        this(method, target, headers, peer, secure, null);
    }

    public Request(String method, String target, RequestHeaders headers) {
        this(method, target, headers, null, false);
    }

    public Request(String method, String target) {
        this(method, target, RequestHeaders.NONE);
    }

    public boolean headOnly() {
        return "HEAD".equals(this.method);
    }

    /** Same request, different target. Keeps headers, peer, secure and protocol. */
    public Request withTarget(String target) {
        return new Request(this.method, target, this.headers, this.peer, this.secure, this.protocol);
    }

    /** Same request, different method. Keeps headers, peer, secure and protocol. */
    public Request withMethod(String method) {
        return new Request(method, this.target, this.headers, this.peer, this.secure, this.protocol);
    }
}
