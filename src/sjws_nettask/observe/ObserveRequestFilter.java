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
package sjws_nettask.observe;

import java.util.List;
import java.util.Objects;
import sjws_nettask.core.FilterChain;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestFilter;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.core.Response;

/**
 * Outermost request filter for access logging, counters and the optional probes.
 * <p>
 * Registered at order 0, before any caller filter of the same order, and only when observability
 * is on (or after {@link sjws_nettask.SjwsServer#metricsSnapshot()} arms it). While it is not
 * installed the transports do not call it: no clock read, no counters, no path check.
 * <p>
 * {@link #filter} samples the clock around {@code proceed}. That stops when the handler has built
 * the response, which is before the transport writes it. Protocol failures that never enter the
 * chain (HTTP/1 parse errors and 408, HTTP/2 refusals, HTTP/3 refusals) are not recorded.
 */
public final class ObserveRequestFilter implements RequestFilter {

    private final ObserverSlot slot;

    public ObserveRequestFilter(ObserverSlot slot) {
        this.slot = Objects.requireNonNull(slot, "slot");
    }

    /**
     * One order-0 filter in front of {@code terminal}. The chain is what
     * {@link RequestFilters#create()}{@code .add(0, filter)} compiles.
     */
    public static FilterChain chain(ObserverSlot slot, Handler terminal) {
        return Objects.requireNonNull(
                RequestFilters.create().add(0, new ObserveRequestFilter(slot)).build(terminal), "chain");
    }

    @Override
    public Response filter(Request request, Chain next) {
        var active = this.slot.get();
        if (active == RequestObserver.NONE) {
            return next.proceed(request);
        }
        // Pin mask names at entry so a reload that clears/shrinks them cannot unmask this request
        // when it logs after proceed. Expanding names stays published before the chain is armed.
        var pinnedNames = active.logging() ? active.credentialQueryParams() : AccessTargetMask.NONE;
        long start = System.nanoTime();
        Response response;
        if (active.intercepts()) {
            var special = active.intercept(request.method(), request.target(), loopback(request));
            response = special != null ? special : next.proceed(request);
        } else {
            response = next.proceed(request);
        }
        long nanos = System.nanoTime() - start;
        int protocol = RequestObserver.protocolId(request.protocol());
        long bytes = response.sendsBody() ? Math.max(0L, response.contentLength()) : 0L;
        if (active.logging()) {
            String referer = "-";
            String agent = "-";
            if (active.logHeaders()) {
                referer = header(request, "referer");
                agent = header(request, "user-agent");
            }
            active.complete(response.status(), protocol, bytes, nanos, remote(request), request.method(),
                    request.target(), request.protocol(), referer, agent, pinnedNames);
        } else {
            active.complete(response.status(), protocol, bytes, nanos);
        }
        return response;
    }

    private static boolean loopback(Request request) {
        var peer = request.peer();
        if (peer == null) {
            return false;
        }
        var address = peer.getAddress();
        return address != null && address.isLoopbackAddress();
    }

    private static String remote(Request request) {
        var peer = request.peer();
        if (peer == null) {
            return "-";
        }
        var address = peer.getAddress();
        return address == null ? "-" : address.getHostAddress();
    }

    private static String header(Request request, String name) {
        List<String> values = request.headers().values(name);
        if (values.isEmpty()) {
            return "-";
        }
        var value = values.get(0);
        return value == null || value.isEmpty() ? "-" : value;
    }
}
