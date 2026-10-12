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

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Stand-in for {@code sjws_nettask.cp}. Every operation throws
 * {@link ControlPlanePendingException}. It does not elect a leader, push config, or
 * answer health. {@link #available()} is false.
 */
public final class PendingControlPlane implements ControlPlane {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public List<ControlNode> start(int voters, ClusterClock clock) {
        throw pending();
    }

    @Override
    public List<ControlNode> nodes() {
        throw pending();
    }

    @Override
    public Optional<ControlNode> leader() {
        throw pending();
    }

    @Override
    public long push(String canonicalYaml) {
        throw pending();
    }

    @Override
    public String contentHash(long version) {
        throw pending();
    }

    @Override
    public long rollback(long toVersion) {
        throw pending();
    }

    @Override
    public void partitionDataFromControl() {
        throw pending();
    }

    @Override
    public void heal() {
        throw pending();
    }

    @Override
    public void killLeader() {
        throw pending();
    }

    @Override
    public void delay(String dataNodeId, Duration oneWay) {
        throw pending();
    }

    @Override
    public void awaitConverged(long version, Duration timeout) {
        throw pending();
    }

    @Override
    public void close() {
        // Nothing was started.
    }

    private static ControlPlanePendingException pending() {
        return new ControlPlanePendingException();
    }
}
