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
package sjws_nettask.admin;

import sjws_nettask.observe.ClusterStatus;

/**
 * Control/status abstraction the admin TUI drives. The in-process implementation is
 * {@link EmbeddedServerControl}; a daemon mode can supply its own (e.g. talking to a running
 * daemon over a local socket or PID file) without the TUI changing.
 * <p>
 * Implementations must be thread-safe: the TUI may poll {@link #status()} while a command runs.
 */
public interface ServerControl extends AutoCloseable {

    /** Snapshot of the server state; never throws, never blocks on I/O for long. */
    ServerStatus status();

    /**
     * Cluster aggregate for the admin TUI, or null when this control is not attached to a
     * control plane. Never throws.
     */
    default ClusterStatus clusterStatus() {
        return null;
    }

    /**
     * Message queues for the Queues view (#384): one row per topic and consumer group, read from
     * Messaging Core snapshots. Empty when messaging is not enabled. Never throws.
     */
    default java.util.List<QueueView> queues() {
        return java.util.List.of();
    }

    /**
     * One page of dead-lettered entries (no payloads) of {@code group} on {@code topic}.
     * @throws ControlException when the topic or group is unknown or messaging is not enabled
     */
    default java.util.List<QueueView.Entry> deadLetters(String topic, String group, long fromOffset, int limit)
            throws ControlException {
        throw new ControlException("messaging is not enabled");
    }

    /**
     * Starts a graceful drain without blocking (#381): stop accepting, let in-flight work finish
     * up to the HA drain timeout, then close. Idempotent: while a drain runs, the same operation
     * is returned. Cancelling a drain is not supported: HA's drain lifecycle has no cancel.
     * @throws ControlException when the server is not running or drain is not supported
     */
    default DrainOperation drain() throws ControlException {
        throw new ControlException("drain is not available for this server");
    }

    /** The last drain started from this control, or null. Never throws. */
    default DrainOperation drainStatus() {
        return null;
    }

    /** Starts the server if stopped. @throws ControlException if it cannot start */
    void start() throws ControlException;

    /** Stops the server if running (graceful). Idempotent. */
    void stop() throws ControlException;

    /** Re-reads the configuration and restarts with it. @throws ControlException on failure */
    void reload() throws ControlException;

    /** Whether {@link #start}, {@link #stop}, {@link #reload} are supported (a read-only view may not). */
    default boolean controllable() {
        return true;
    }

    /** Releases resources; for the embedded control this stops the server. */
    @Override
    void close();

    /** A control command failed; the message is shown to the admin. */
    final class ControlException extends Exception {

        private static final long serialVersionUID = 1L;

        public ControlException(String message, Throwable cause) {
            super(message, cause);
        }

        public ControlException(String message) {
            super(message);
        }
    }
}
