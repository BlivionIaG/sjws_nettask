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
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One virtual thread, one reused authenticated datagram. {@link #cycle()} is the same step the
 * thread runs, exposed so tests can advance a fake clock without sleeping. Every datagram is
 * HMAC-SHA256; a missing cluster key is refused.
 */
public final class HeartbeatService implements AutoCloseable {

    private final Membership membership;
    private final PacketLink link;
    private final Clock clock;
    private final Duration interval;
    private final ClusterEvents events;
    private final HeartbeatAuthenticator auth;
    private final ReentrantLock lock = new ReentrantLock();
    private final byte[] body = new byte[HeartbeatCodec.BYTES];
    private final byte[] packet = new byte[HeartbeatAuthenticator.PACKET_BYTES];
    private final Map<NodeId, Long> lastSendNanos = new HashMap<>();
    private final AtomicInteger received = new AtomicInteger();
    private final AtomicInteger sendFailures = new AtomicInteger();
    private volatile boolean running;
    private Thread thread;
    private long term;
    private long generation;
    private long contentHash;
    private int leaseRemainingMs;

    public HeartbeatService(Membership membership, PacketLink link, Clock clock, Duration interval,
            ClusterEvents events, ClusterKey key) {
        this.membership = Objects.requireNonNull(membership, "membership");
        this.link = Objects.requireNonNull(link, "link");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
        this.auth = new HeartbeatAuthenticator(Objects.requireNonNull(key, "cluster key"));
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval");
        }
        this.interval = interval;
    }

    public void announce(long term, long generation, long contentHash, int leaseRemainingMs) {
        lock.lock();
        try {
            this.term = term;
            this.generation = generation;
            this.contentHash = contentHash;
            this.leaseRemainingMs = Math.max(0, leaseRemainingMs);
        } finally {
            lock.unlock();
        }
    }

    public int received() {
        return received.get();
    }

    public int sendFailures() {
        return sendFailures.get();
    }

    /** Authenticated datagrams that were dropped (bad tag, replay, stale, or a bad body). */
    public int rejected() {
        return this.auth.rejected();
    }

    public void start() {
        lock.lock();
        try {
            if (thread != null) {
                return;
            }
            running = true;
            thread = Thread.ofVirtual().name("ha-heartbeat-" + membership.self().name()).start(this::loop);
        } finally {
            lock.unlock();
        }
    }

    private void loop() {
        while (running) {
            try {
                cycle();
                clock.sleep(interval);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException failed) {
                sendFailures.incrementAndGet();
                try {
                    clock.sleep(interval);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    public void cycle() {
        long now = clock.nanoTime();
        membership.tick(now);
        long termNow;
        long generationNow;
        long hashNow;
        int leaseNow;
        lock.lock();
        try {
            termNow = term;
            generationNow = generation;
            hashNow = contentHash;
            leaseNow = leaseRemainingMs;
        } finally {
            lock.unlock();
        }
        for (var peer : membership.view().others()) {
            var beat = membership.outbound(peer.id(), termNow, generationNow, hashNow, leaseNow);
            HeartbeatCodec.encode(beat, this.body);
            this.auth.seal(this.body, this.packet, System.currentTimeMillis());
            lastSendNanos.put(peer.id(), now);
            try {
                link.send(peer.address(), this.packet, 0, HeartbeatAuthenticator.PACKET_BYTES);
            } catch (IOException failed) {
                sendFailures.incrementAndGet();
            }
        }
        int spins = 0;
        while (spins++ < 64) {
            PacketLink.Packet packet;
            try {
                packet = link.poll();
            } catch (IOException failed) {
                sendFailures.incrementAndGet();
                return;
            }
            if (packet == null) {
                return;
            }
            accept(packet, clock.nanoTime());
        }
    }

    private void accept(PacketLink.Packet packet, long now) {
        var beat = this.auth.open(packet.data(), System.currentTimeMillis());
        if (beat == null) {
            return;
        }
        received.incrementAndGet();
        var sender = membership.view().memberByWire(beat.fromWire());
        if (sender.isPresent()) {
            var sent = lastSendNanos.get(sender.get().id());
            if (sent != null) {
                events.heartbeatRtt(sender.get().id(), Math.max(0L, now - sent));
            }
        }
        var reply = membership.onHeartbeat(beat, now);
        if (reply.isEmpty()) {
            return;
        }
        HeartbeatCodec.encode(reply.get(), this.body);
        this.auth.seal(this.body, this.packet, System.currentTimeMillis());
        try {
            link.send(packet.from(), this.packet, 0, HeartbeatAuthenticator.PACKET_BYTES);
        } catch (IOException failed) {
            sendFailures.incrementAndGet();
        }
    }

    @Override
    public void close() {
        running = false;
        Thread current;
        lock.lock();
        try {
            current = thread;
        } finally {
            lock.unlock();
        }
        if (current != null) {
            current.interrupt();
            try {
                current.join(1_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
