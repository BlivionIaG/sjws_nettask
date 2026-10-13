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
package sjws_nettask.acme;

import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * In-process {@link ControlLeadership}. {@link #standalone()} is already leader-ready so
 * today's ACME path runs. A control node starts from {@link #control()} and becomes ready
 * only when {@link #ready(long, long)} runs, which is the election-NOOP signal Raft will
 * forward later.
 */
public final class LocalLeadership implements ControlLeadership {

    private final CopyOnWriteArrayList<BiConsumer<Long, Optional<String>>> changes = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<BiConsumer<Long, Long>> readies = new CopyOnWriteArrayList<>();
    private volatile boolean leader;
    private volatile long term;
    private volatile String leaderId;
    private volatile boolean noopApplied;
    private volatile long appliedIndex;

    public LocalLeadership(boolean leader) {
        this.leader = leader;
    }

    /** Leader of term 1, NOOP already applied. Used when the process is standalone. */
    public static LocalLeadership standalone() {
        var local = new LocalLeadership(true);
        local.term = 1;
        local.leaderId = "local";
        local.noopApplied = true;
        local.appliedIndex = 1;
        return local;
    }

    /** Not leader and not ready. ACME stays idle until {@link #ready(long, long)}. */
    public static LocalLeadership control() {
        return new LocalLeadership(false);
    }

    @Override
    public boolean isLeader() {
        return this.leader;
    }

    @Override
    public void onLeaderChange(BiConsumer<Long, Optional<String>> listener) {
        this.changes.add(listener);
        if (this.term != 0) {
            listener.accept(this.term, Optional.ofNullable(this.leaderId));
        }
    }

    @Override
    public void leaderReady(BiConsumer<Long, Long> listener) {
        this.readies.add(listener);
        if (this.noopApplied) {
            listener.accept(this.term, this.appliedIndex);
        }
    }

    /**
     * Leadership moved. Clears leader-ready. {@code leaderId} may be null. Does not fire
     * {@link #leaderReady}.
     */
    public void change(long term, String leaderId, boolean leader) {
        this.term = term;
        this.leaderId = leaderId;
        this.leader = leader;
        this.noopApplied = false;
        var id = Optional.ofNullable(leaderId);
        for (var listener : this.changes) {
            listener.accept(term, id);
        }
    }

    /** This leader's election NOOP is applied at {@code appliedIndex}. */
    public void ready(long term, long appliedIndex) {
        this.term = term;
        this.appliedIndex = appliedIndex;
        this.leader = true;
        this.noopApplied = true;
        if (this.leaderId == null) {
            this.leaderId = "local";
        }
        for (var listener : this.readies) {
            listener.accept(term, appliedIndex);
        }
    }
}
