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
package sjws_nettask.clusterharness;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * What the cluster harness needs from {@code sjws_nettask.cp} (design
 * {@link ControlPlaneDesign#PR}).
 *
 * <p>An implementation reads {@link ClusterClock} for election, heartbeat, suspect, and
 * {@link #delay}. It binds each voter's admin listener on port 0 and returns that port from
 * {@link ControlNode#adminPort()}. Fault methods are in-process: they do not send
 * {@code kill -9} and they do not require an mTLS admin certificate.
 *
 * <p>{@link CpStoreControlPlane} runs real {@code CpStore} voters. {@link PendingControlPlane}
 * refuses every call; the harness uses it only when no control plane was asked for.
 */
public interface ControlPlane extends AutoCloseable {

    /** True when 3 or 5 in-process {@code cp-config} voters can run. */
    boolean available();

    /**
     * Start {@code voters} control nodes (3 or 5) on the injected clock.
     * Each admin port is ephemeral.
     */
    List<ControlNode> start(int voters, ClusterClock clock) throws IOException;

    List<ControlNode> nodes();

    /** The voter whose {@code leaderReady} is true, or empty during an election. */
    Optional<ControlNode> leader();

    /**
     * Validate, propose, and activate canonical YAML (design §9).
     * Returns the committed desired-config version.
     */
    long push(String canonicalYaml);

    /** Content hash of a retained activated version (design §4 keeps 8). */
    String contentHash(long version);

    /**
     * Append {@code Rollback} to {@code toVersion} (design §9, {@code POST /cp/v1/rollback}).
     * The returned version is strictly higher. Its content hash equals {@code contentHash(toVersion)}.
     */
    long rollback(long toVersion);

    /**
     * Isolate every data node from every control voter (design §10).
     * Data nodes keep the installed snapshot. {@link #heal()} restores the watch.
     */
    void partitionDataFromControl();

    /** Restore links cut by {@link #partitionDataFromControl} or {@link #killLeader}. */
    void heal();

    /**
     * Stop the current leader in-process and leave the remaining voters able to elect.
     * Honors {@link ClusterClock}: one election-timeout {@link ClusterClock#advance} is enough
     * for a survivor to become {@code leaderReady}.
     */
    void killLeader();

    /**
     * One-way watch delay for a data node that still acks (design §8, per-node window).
     * The delay is measured on {@link ClusterClock}, so {@link ClusterClock#advance} releases it.
     * Other nodes are unaffected.
     */
    void delay(String dataNodeId, Duration oneWay);

    /**
     * Wait until every data node reports {@code version}. The deadline is real time spent in
     * this method; election and delay themselves move only when the clock advances.
     */
    void awaitConverged(long version, Duration timeout);

    @Override
    void close();
}
