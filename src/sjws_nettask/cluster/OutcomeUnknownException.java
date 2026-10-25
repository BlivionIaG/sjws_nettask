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

import java.util.Optional;

/**
 * The log <b>accepted</b> the write behind this future, but its fate is unknown: it may still commit (it can already
 * be on a follower, or on this node's disk, and a later leader may commit it) or it may be discarded. Raised when the
 * node stops leading before the write committed (step-down, leadership transfer, an election started as leader, a
 * same-term demotion), when the group fails closed after a log write it could not roll back (the storage error is
 * the cause), and when the node closes with the write still pending.
 *
 * <p>Handle it like a timeout: never resend the write as if nothing had been appended. Retry only with the caller's
 * own deduplication (an idempotency key, a producer sequence), or read back to see whether it landed. Deliberately
 * <b>not</b> a {@link NotLeaderException}: that one means the write was refused before it entered the log, so a
 * plain retry (or a redirect to the leader) is safe.
 */
public final class OutcomeUnknownException extends Exception {

    private static final long serialVersionUID = 1L;

    private final GroupId groupId;
    private final transient Optional<NodeId> hint;

    /**
     * @param why what happened to the write, e.g. {@code "this node stopped leading"}
     * @param hint the leader this node knows of, if any (where to read back or retry)
     * @param cause the underlying error (a storage failure when failing closed), or {@code null}
     */
    public OutcomeUnknownException(GroupId groupId, String why, Optional<NodeId> hint, Throwable cause) {
        super(groupId + ": " + why + "; outcome unknown, the write may still commit", cause);
        this.groupId = groupId;
        this.hint = hint == null ? Optional.empty() : hint;
    }

    public GroupId groupId() {
        return groupId;
    }

    /** The leader this node knew of when it gave the write up; empty when unknown. */
    public Optional<NodeId> hint() {
        return hint == null ? Optional.empty() : hint;
    }
}
