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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;

/** Test helper: a {@link Body.Stream.Source} over fixed chunks that records {@link #close()}. */
public final class TestSource implements Body.Stream.Source {
    private final ArrayDeque<byte[]> chunks;
    private final boolean direct;
    public int closes;
    public java.io.IOException failWith;

    public TestSource(boolean direct, byte[]... chunks) {
        this.chunks = new ArrayDeque<>(List.of(chunks));
        this.direct = direct;
    }

    @Override
    public ByteBuffer next() throws java.io.IOException {
        var c = this.chunks.poll();
        if (c == null) {
            if (this.failWith != null) {
                throw this.failWith;
            }
            return null;
        }
        if (this.direct) {
            var b = ByteBuffer.allocateDirect(c.length);
            b.put(c).flip();
            return b;
        }
        return ByteBuffer.wrap(c);
    }

    @Override
    public void close() {
        this.closes++;
    }
}
