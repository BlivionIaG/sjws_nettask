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
package sjws_nettask.cluster;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-process datagram fabric. {@link #block} drops one direction; {@link #dropNext} drops the
 * next N sends from any attached link. Tests use this instead of sleeping on a real socket.
 */
public final class SimNetwork {

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<InetSocketAddress, ArrayDeque<PacketLink.Packet>> inbox = new HashMap<>();
    private final Set<Directed> blocked = new HashSet<>();
    private int dropRemaining;
    private boolean closed;

    public PacketLink link(InetSocketAddress local) {
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("closed");
            }
            if (inbox.containsKey(local)) {
                throw new IllegalArgumentException("duplicate address " + local);
            }
            inbox.put(local, new ArrayDeque<>());
            return new Link(local);
        } finally {
            lock.unlock();
        }
    }

    public void block(InetSocketAddress from, InetSocketAddress to) {
        lock.lock();
        try {
            blocked.add(new Directed(from, to));
        } finally {
            lock.unlock();
        }
    }

    public void unblock(InetSocketAddress from, InetSocketAddress to) {
        lock.lock();
        try {
            blocked.remove(new Directed(from, to));
        } finally {
            lock.unlock();
        }
    }

    public void dropNext(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("n");
        }
        lock.lock();
        try {
            dropRemaining += n;
        } finally {
            lock.unlock();
        }
    }

    public void close() {
        lock.lock();
        try {
            closed = true;
            inbox.clear();
        } finally {
            lock.unlock();
        }
    }

    private record Directed(InetSocketAddress from, InetSocketAddress to) {
    }

    private final class Link implements PacketLink {
        private final InetSocketAddress local;

        private Link(InetSocketAddress local) {
            this.local = local;
        }

        @Override
        public void send(InetSocketAddress to, byte[] packet, int offset, int length) throws IOException {
            var copy = new byte[length];
            System.arraycopy(packet, offset, copy, 0, length);
            lock.lock();
            try {
                if (closed) {
                    throw new IOException("closed");
                }
                if (dropRemaining > 0) {
                    dropRemaining--;
                    return;
                }
                if (blocked.contains(new Directed(local, to))) {
                    return;
                }
                var queue = inbox.get(to);
                if (queue == null) {
                    throw new IllegalArgumentException("unknown destination " + to);
                }
                queue.add(new Packet(local, copy));
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Packet poll() throws IOException {
            lock.lock();
            try {
                if (closed) {
                    throw new IOException("closed");
                }
                return inbox.get(local).poll();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public InetSocketAddress localAddress() {
            return local;
        }

        @Override
        public void close() {
            // The network owns the queues; closing one endpoint leaves the others up.
        }
    }
}
