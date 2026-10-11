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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Socket input stream with an idle timeout per read plus an optional overall
 * deadline (protects against slowloris-style trickling).
 * <p>
 * KNOWN COST (design review, section 3.2): calling {@code Socket.setSoTimeout} per refill on a
 * virtual thread roughly halves HTTP/1 throughput. Accepted until S4, where the channel-based
 * transport replaces this class with a reaper enforcing read and write deadlines. Don't build on it.
 */
final class TimedInputStream extends FilterInputStream {

    private final Socket socket;
    private final int idleTimeoutMs;
    private long deadlineNanos; // 0 = none
    private int armedTimeout = -1;

    TimedInputStream(InputStream in, Socket socket, int idleTimeoutMs) {
        super(in);
        this.socket = socket;
        this.idleTimeoutMs = idleTimeoutMs;
    }

    void setDeadline(long timeoutMs) {
        this.deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
        if (this.deadlineNanos == 0) {
            this.deadlineNanos = 1;
        }
    }

    void clearDeadline() {
        this.deadlineNanos = 0;
    }

    boolean deadlineExpired() {
        return this.deadlineNanos != 0 && System.nanoTime() - this.deadlineNanos >= 0;
    }

    private void arm() throws IOException {
        int timeout = this.idleTimeoutMs;
        if (this.deadlineNanos != 0) {
            long remainingMs = (this.deadlineNanos - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) {
                throw new SocketTimeoutException("request deadline exceeded");
            }
            timeout = (int) Math.min(timeout, remainingMs);
        }
        if (timeout != this.armedTimeout) {
            this.socket.setSoTimeout(timeout);
            this.armedTimeout = timeout;
        }
    }

    @Override
    public int read() throws IOException {
        arm();
        return super.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        arm();
        return super.read(b, off, len);
    }
}
