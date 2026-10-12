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
 * One stage between protocol decoding and the terminal {@link Handler}.
 * <p>
 * Implementations must be safe for concurrent requests (the chain calls them on the request
 * thread of whichever transport accepted the connection, with no extra lock). Call
 * {@link Chain#proceed} synchronously, on that same thread. A second call on that thread throws
 * {@link IllegalStateException} and does not run the rest of the chain again; the filter that
 * called it then becomes a 500 unless it catches the exception. Skip the call to answer the
 * request here. The response from {@code proceed} may be returned as-is, returned with a
 * different status or body, or modified through {@link Response#headers()} (that map is not
 * copied by the chain). {@link Response} is a record; its headers are mutable.
 * <p>
 * A runtime exception, or a null return, becomes the standard 500 page
 * ({@link StaticFileHandler#error}). Outer filters then observe that 500 as the value
 * {@code proceed} returned; the connection stays open. Time spent in {@code proceed} is the
 * time until the downstream handler has <em>built</em> the response, which is before the
 * transport writes it.
 */
@FunctionalInterface
public interface RequestFilter {

    Response filter(Request request, Chain next);

    /** The remainder of the chain, ending at the terminal handler. Shared across requests. */
    @FunctionalInterface
    interface Chain {

        /**
         * Runs the next filter, or the terminal handler when this was the last one.
         * Once per entry of the chain on this thread: a second call throws
         * {@link IllegalStateException} and does not run downstream again.
         *
         * @param request the request to pass down (the original, or a replacement)
         */
        Response proceed(Request request);
    }
}
