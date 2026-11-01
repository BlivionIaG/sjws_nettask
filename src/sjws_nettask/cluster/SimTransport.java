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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One in-process transport for every replication group. Frames are FIFO and demuxed by
 * {@link GroupId}. {@link #cut} drops both directions; delivery is explicit so tests can
 * interleave groups without sleeping.
 */
public final class SimTransport implements ClusterTransport {

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<NodeId, BiConsumer<NodeId, Frame>> handlers = new HashMap<>();
    private final ArrayDeque<Envelope> queue = new ArrayDeque<>();
    private final Set<Directed> blocked = new HashSet<>();
    private final List<GroupId> delivered = new ArrayList<>();

    @Override
    public ClusterRpc attach(NodeId self, BiConsumer<NodeId, Frame> handler) {
        lock.lock();
        try {
            if (handlers.containsKey(self)) {
                throw new IllegalArgumentException("duplicate " + self);
            }
            handlers.put(self, handler);
            return new Endpoint(self);
        } finally {
            lock.unlock();
        }
    }

    /** Drops {@code self}'s handler and its queued frames, so a restarted node can attach again. */
    @Override
    public void detach(NodeId self) {
        lock.lock();
        try {
            handlers.remove(self);
            queue.removeIf(envelope -> envelope.to.equals(self) || envelope.from.equals(self));
        } finally {
            lock.unlock();
        }
    }

    public void cut(NodeId a, NodeId b) {
        lock.lock();
        try {
            blocked.add(new Directed(a, b));
            blocked.add(new Directed(b, a));
        } finally {
            lock.unlock();
        }
    }

    public void heal(NodeId a, NodeId b) {
        lock.lock();
        try {
            blocked.remove(new Directed(a, b));
            blocked.remove(new Directed(b, a));
        } finally {
            lock.unlock();
        }
    }

    /** Deliver up to {@code max} queued frames. Returns how many handlers ran. */
    @Override
    public int deliver(int max) {
        int deliveredNow = 0;
        while (deliveredNow < max) {
            Envelope next;
            lock.lock();
            try {
                next = queue.poll();
            } finally {
                lock.unlock();
            }
            if (next == null) {
                return deliveredNow;
            }
            BiConsumer<NodeId, Frame> handler;
            lock.lock();
            try {
                handler = handlers.get(next.to);
                delivered.add(next.frame.group());
            } finally {
                lock.unlock();
            }
            if (handler != null) {
                handler.accept(next.from, next.frame);
            }
            deliveredNow++;
        }
        return deliveredNow;
    }

    public List<GroupId> trace() {
        lock.lock();
        try {
            return List.copyOf(delivered);
        } finally {
            lock.unlock();
        }
    }

    public record Frame(GroupId group, int type, byte[] body) {
        public Frame {
            if (group == null || body == null) {
                throw new NullPointerException("frame");
            }
            body = body.clone();
        }
    }

    private record Envelope(NodeId from, NodeId to, Frame frame) {
    }

    private record Directed(NodeId from, NodeId to) {
    }

    private final class Endpoint implements ClusterRpc {
        private final NodeId self;

        private Endpoint(NodeId self) {
            this.self = self;
        }

        @Override
        public void send(NodeId to, Frame frame) {
            lock.lock();
            try {
                if (blocked.contains(new Directed(self, to)) || !handlers.containsKey(to)) {
                    return;
                }
                queue.add(new Envelope(self, to, frame));
            } finally {
                lock.unlock();
            }
        }
    }
}
