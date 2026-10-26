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

import java.io.IOException;

/**
 * Pull-style request content attached by the transport. Read on the request thread, at most once
 * from start to end; whatever a handler leaves unread is discarded by the transport (or the
 * connection is closed). HTTP/1.1 attaches one for {@code Content-Length} bodies of methods other
 * than GET/HEAD; HTTP/3 for every method other than GET/HEAD (length -1 when no {@code content-length}
 * was sent and content follows); HTTP/2 attaches none yet.
 */
public interface RequestBody {

    /** No content. */
    RequestBody EMPTY = new RequestBody() {
        @Override
        public long length() {
            return 0;
        }

        @Override
        public int read(byte[] into, int off, int len) {
            return -1;
        }
    };

    /** @return content length in bytes, or -1 when unknown */
    long length();

    /**
     * Reads up to {@code len} bytes, blocking until at least one is available.
     *
     * @return bytes read, or -1 at the end of the body
     * @throws IOException if the client went away or a deadline expired before the end
     */
    int read(byte[] into, int off, int len) throws IOException;
}
