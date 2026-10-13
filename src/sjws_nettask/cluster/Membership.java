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

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Static-seed failure detector. Suspect after {@link #SUSPECT_AFTER_MISSES} missed intervals,
 * dead after {@code deadAfterNanos} of silence. A higher incarnation refutes a suspicion.
 * When at least three members are configured, a suspect is probed indirectly through up to
 * {@link #INDIRECT_K} other alive members.
 */
public final class Membership {

    public static final int SUSPECT_AFTER_MISSES = 3;
    public static final int INDIRECT_K = 2;

    private final ReentrantLock lock = new ReentrantLock();
    private final NodeId self;
    private final long intervalNanos;
    private final long deadAfterNanos;
    private final ClusterEvents events;
    private final Map<NodeId, Slot> slots = new LinkedHashMap<>();
    private final Map<Integer, Slot> byWire = new LinkedHashMap<>();
    private final List<Consumer<MembershipView>> listeners = new ArrayList<>();
    private long selfIncarnation;
    private int seq;

    public Membership(NodeId self, List<Member> members, long intervalNanos, long deadAfterNanos, long nowNanos,
            ClusterEvents events) {
        this.self = Objects.requireNonNull(self, "self");
        this.events = Objects.requireNonNull(events, "events");
        if (intervalNanos < 1 || deadAfterNanos < 1) {
            throw new IllegalArgumentException("intervals");
        }
        this.intervalNanos = intervalNanos;
        this.deadAfterNanos = deadAfterNanos;
        boolean foundSelf = false;
        for (var member : members) {
            Objects.requireNonNull(member, "member");
            if (slots.containsKey(member.id()) || byWire.containsKey(member.id().wire())) {
                throw new IllegalArgumentException("duplicate member " + member.id());
            }
            var slot = new Slot(member, nowNanos);
            slots.put(member.id(), slot);
            byWire.put(member.id().wire(), slot);
            if (member.id().equals(self)) {
                foundSelf = true;
                selfIncarnation = member.incarnation();
                slot.state = MemberState.ALIVE;
            }
        }
        if (!foundSelf) {
            throw new IllegalArgumentException("self is not in the seed list");
        }
    }

    /** Silence after which a member is dead. Heartbeat replay uses this to allow an earlier boot epoch. */
    public long deadAfterNanos() {
        return this.deadAfterNanos;
    }

    public NodeId self() {
        return self;
    }

    public long selfIncarnation() {
        lock.lock();
        try {
            return selfIncarnation;
        } finally {
            lock.unlock();
        }
    }

    public void onMembershipChange(Consumer<MembershipView> listener) {
        Objects.requireNonNull(listener, "listener");
        lock.lock();
        try {
            listeners.add(listener);
        } finally {
            lock.unlock();
        }
    }

    public MembershipView view() {
        lock.lock();
        try {
            return snapshot();
        } finally {
            lock.unlock();
        }
    }

    public boolean leaseHeld(NodeId id, long nowNanos) {
        lock.lock();
        try {
            var slot = slots.get(id);
            return slot != null && slot.leaseExpiresNanos > nowNanos;
        } finally {
            lock.unlock();
        }
    }

    public long generationOf(NodeId id) {
        lock.lock();
        try {
            var slot = slots.get(id);
            return slot == null ? -1L : slot.generation;
        } finally {
            lock.unlock();
        }
    }

    /** Advance the failure detector to {@code nowNanos}. Notifies listeners only on a state change. */
    public void tick(long nowNanos) {
        Effect effect = new Effect();
        lock.lock();
        try {
            for (var slot : slots.values()) {
                if (slot.id.equals(self) || slot.state == MemberState.DEAD) {
                    continue;
                }
                long silent = nowNanos - slot.lastHeardNanos;
                if (silent < 0) {
                    silent = 0;
                }
                slot.misses = (int) Math.min(Integer.MAX_VALUE, silent / intervalNanos);
                if (silent >= deadAfterNanos) {
                    note(effect, slot, MemberState.DEAD);
                } else if (slot.state == MemberState.ALIVE && slot.misses >= SUSPECT_AFTER_MISSES) {
                    note(effect, slot, MemberState.SUSPECT);
                    slot.suspectSinceNanos = nowNanos;
                    selectProbers(slot);
                }
            }
            seal(effect);
        } finally {
            lock.unlock();
        }
        dispatch(effect);
    }

    /**
     * Apply one heartbeat. A stale incarnation is ignored. Suspicion of ourselves bumps our
     * incarnation and is answered with an ack the caller should send.
     */
    public Optional<Heartbeat> onHeartbeat(Heartbeat beat, long nowNanos) {
        Effect effect = new Effect();
        lock.lock();
        try {
            if (beat.fromWire() == self.wire()) {
                return Optional.empty();
            }
            var sender = byWire.get(beat.fromWire());
            if (sender == null) {
                return Optional.empty();
            }
            if (beat.suspectWire() == self.wire() && beat.ackIncarnation() >= selfIncarnation) {
                selfIncarnation++;
                var mine = slots.get(self);
                mine.incarnation = selfIncarnation;
                effect.changed = true;
                effect.reply = Optional.of(reply(beat, Heartbeat.Kind.ACK, 0, selfIncarnation));
            }
            if (beat.incarnation() < sender.incarnation) {
                seal(effect);
                // Fall through to dispatch after unlock via the local reply return below.
            } else {
                if (beat.incarnation() > sender.incarnation) {
                    sender.incarnation = beat.incarnation();
                    effect.changed = true;
                }
                if (sender.state != MemberState.ALIVE) {
                    note(effect, sender, MemberState.ALIVE);
                }
                sender.lastHeardNanos = nowNanos;
                sender.misses = 0;
                sender.probers.clear();
                sender.term = beat.term();
                sender.generation = beat.generation();
                sender.contentHash = beat.contentHash();
                long leaseMs = beat.leaseRemainingMs();
                if (leaseMs > 0) {
                    long capped = Math.min(leaseMs, 86_400_000L);
                    sender.leaseExpiresNanos = nowNanos + capped * 1_000_000L;
                } else {
                    sender.leaseExpiresNanos = nowNanos;
                }
                if (beat.kind() == Heartbeat.Kind.INDIRECT_ACK && beat.suspectWire() != 0
                        && beat.suspectWire() != self.wire()) {
                    reviveFromIndirect(beat, nowNanos, effect);
                }
                if (effect.reply.isEmpty() && beat.suspectWire() != 0 && beat.suspectWire() != self.wire()
                        && (beat.kind() == Heartbeat.Kind.INDIRECT_PING || beat.kind() == Heartbeat.Kind.HEARTBEAT)) {
                    var target = byWire.get(beat.suspectWire());
                    if (target != null && target.state == MemberState.ALIVE && !target.id.equals(sender.id)) {
                        effect.reply = Optional.of(
                                reply(beat, Heartbeat.Kind.INDIRECT_ACK, target.id.wire(), target.incarnation));
                    }
                }
                seal(effect);
            }
        } finally {
            lock.unlock();
        }
        var reply = effect.reply;
        dispatch(effect);
        return reply;
    }

    /** Next datagram for {@code to}. Suspects are named so they can refute, and probers are asked. */
    public Heartbeat outbound(NodeId to, long term, long generation, long hash, int leaseRemainingMs) {
        lock.lock();
        try {
            if (!slots.containsKey(to) || to.equals(self)) {
                throw new IllegalArgumentException("peer");
            }
            seq++;
            int suspectWire = 0;
            long ackIncarnation = 0;
            var kind = Heartbeat.Kind.HEARTBEAT;
            Slot suspected = null;
            for (var slot : slots.values()) {
                if (slot.state == MemberState.SUSPECT) {
                    suspected = slot;
                    break;
                }
            }
            if (suspected != null && suspected.id.equals(to)) {
                suspectWire = suspected.id.wire();
                ackIncarnation = suspected.incarnation;
            } else if (suspected != null && suspected.probers.contains(to)) {
                kind = Heartbeat.Kind.INDIRECT_PING;
                suspectWire = suspected.id.wire();
                ackIncarnation = suspected.incarnation;
            }
            var mine = slots.get(self);
            mine.term = term;
            mine.generation = generation;
            mine.contentHash = hash;
            int flags = mine.witness ? Heartbeat.FLAG_WITNESS : 0;
            return new Heartbeat(Heartbeat.VERSION, kind, self.wire(), to.wire(), selfIncarnation, term, generation,
                    hash, leaseRemainingMs, flags, seq, suspectWire, ackIncarnation);
        } finally {
            lock.unlock();
        }
    }

    private void reviveFromIndirect(Heartbeat beat, long nowNanos, Effect effect) {
        var target = byWire.get(beat.suspectWire());
        if (target == null || beat.ackIncarnation() < target.incarnation || target.state == MemberState.ALIVE) {
            return;
        }
        if (beat.ackIncarnation() > target.incarnation) {
            target.incarnation = beat.ackIncarnation();
        }
        target.lastHeardNanos = nowNanos;
        target.misses = 0;
        target.probers.clear();
        note(effect, target, MemberState.ALIVE);
    }

    private void selectProbers(Slot suspect) {
        suspect.probers.clear();
        if (slots.size() < 3) {
            return;
        }
        for (var other : slots.values()) {
            if (other.id.equals(self) || other.id.equals(suspect.id)) {
                continue;
            }
            if (other.state != MemberState.ALIVE) {
                continue;
            }
            suspect.probers.add(other.id);
            if (suspect.probers.size() == INDIRECT_K) {
                return;
            }
        }
    }

    private Heartbeat reply(Heartbeat beat, Heartbeat.Kind kind, int suspectWire, long ackIncarnation) {
        var mine = slots.get(self);
        int flags = mine.witness ? Heartbeat.FLAG_WITNESS : 0;
        return new Heartbeat(Heartbeat.VERSION, kind, self.wire(), beat.fromWire(), mine.incarnation, mine.term,
                mine.generation, mine.contentHash, 0, flags, beat.seq(), suspectWire, ackIncarnation);
    }

    private void note(Effect effect, Slot slot, MemberState to) {
        if (slot.state == to) {
            return;
        }
        var from = slot.state;
        slot.state = to;
        effect.changed = true;
        effect.notes.add(new StateNote(slot.id, from, to));
    }

    private void seal(Effect effect) {
        if (!effect.changed) {
            return;
        }
        effect.view = snapshot();
        effect.listeners = List.copyOf(listeners);
    }

    private MembershipView snapshot() {
        var members = new ArrayList<Member>(slots.size());
        for (var slot : slots.values()) {
            members.add(new Member(slot.id, slot.site, slot.address, slot.incarnation, slot.state, slot.witness));
        }
        return new MembershipView(self, members);
    }

    private void dispatch(Effect effect) {
        for (var note : effect.notes) {
            events.memberState(note.id, note.from, note.to);
        }
        if (!effect.changed) {
            return;
        }
        for (var listener : effect.listeners) {
            listener.accept(effect.view);
        }
    }

    private static final class Slot {
        final NodeId id;
        final SiteId site;
        final InetSocketAddress address;
        final boolean witness;
        long incarnation;
        MemberState state;
        long lastHeardNanos;
        long suspectSinceNanos;
        int misses;
        long leaseExpiresNanos;
        long term;
        long generation;
        long contentHash;
        final List<NodeId> probers = new ArrayList<>();

        Slot(Member member, long nowNanos) {
            this.id = member.id();
            this.site = member.site();
            this.address = member.address();
            this.witness = member.witness();
            this.incarnation = member.incarnation();
            this.state = member.state();
            this.lastHeardNanos = nowNanos;
        }
    }

    private static final class StateNote {
        final NodeId id;
        final MemberState from;
        final MemberState to;

        StateNote(NodeId id, MemberState from, MemberState to) {
            this.id = id;
            this.from = from;
            this.to = to;
        }
    }

    private static final class Effect {
        boolean changed;
        MembershipView view;
        List<Consumer<MembershipView>> listeners = List.of();
        final List<StateNote> notes = new ArrayList<>();
        Optional<Heartbeat> reply = Optional.empty();
    }
}
