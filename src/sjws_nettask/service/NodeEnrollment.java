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
package sjws_nettask.service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One control or data process's bootstrap, status file, and join token. The HTTP request path
 * does not read it. The control-plane workstream attaches a {@link ControlPlaneBinding} and calls
 * {@link #tokenConsumed()} after the join commits. There is one enrollment per process.
 */
public final class NodeEnrollment {

    private static final AtomicReference<NodeEnrollment> CURRENT = new AtomicReference<>();
    private static final ReentrantLock OPEN = new ReentrantLock();

    private final ReentrantLock lock = new ReentrantLock();
    private final Bootstrap bootstrap;
    private final Path pidFile;
    private final long pid;
    private final long startedMillis;
    private final long drainBudgetMs;
    private final AtomicReference<ControlPlaneBinding> binding = new AtomicReference<>();
    private boolean closed;

    private NodeEnrollment(Bootstrap bootstrap, Path pidFile, long pid, long startedMillis, long drainBudgetMs) {
        this.bootstrap = bootstrap;
        this.pidFile = pidFile;
        this.pid = pid;
        this.startedMillis = startedMillis;
        this.drainBudgetMs = drainBudgetMs;
    }

    /** The live enrollment, or null in a standalone process (this class is not loaded there). */
    public static NodeEnrollment current() {
        return CURRENT.get();
    }

    static NodeEnrollment open(Bootstrap bootstrap, Path pidFile, long drainBudgetMs) throws IOException {
        long pid = ProcessHandle.current().pid();
        long started = ProcessHandle.current().info().startInstant().map(Instant::toEpochMilli).orElse(-1L);
        var enrollment = new NodeEnrollment(bootstrap, pidFile, pid, started, drainBudgetMs);
        OPEN.lock();
        try {
            if (CURRENT.get() != null) {
                throw new BootstrapException("this process already has a control-plane enrollment");
            }
            enrollment.rewrite();
            CURRENT.set(enrollment);
            return enrollment;
        } finally {
            OPEN.unlock();
        }
    }

    public Bootstrap bootstrap() {
        return this.bootstrap;
    }

    public ControlPlaneBinding binding() {
        return this.binding.get();
    }

    /**
     * Replaces the pending view and rewrites the status file. Does not recreate the file after
     * {@link #close()}.
     */
    public void attach(ControlPlaneBinding next) throws IOException {
        if (next == null) {
            throw new IllegalArgumentException("binding");
        }
        this.lock.lock();
        try {
            if (this.closed) {
                throw new IllegalStateException("enrollment is closed");
            }
            this.binding.set(next);
            rewrite();
        } finally {
            this.lock.unlock();
        }
    }

    /** A clone of the join secret. The caller wipes the clone. Throws when no token is loaded. */
    public byte[] copySecret() {
        var join = this.bootstrap.join();
        if (join == null) {
            throw new IllegalStateException("no join token is loaded");
        }
        return join.copySecret();
    }

    /**
     * The control plane has committed the join. Writes {@code node.id} and deletes the token file.
     * Idempotent after the token is gone.
     */
    public void tokenConsumed() throws IOException {
        this.lock.lock();
        try {
            if (this.closed) {
                throw new IllegalStateException("enrollment is closed");
            }
            this.bootstrap.markJoined();
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Drops the status file and zeros the in-memory token. Does not delete the token file; that
     * happens only in {@link #tokenConsumed()}. Safe to call twice.
     */
    public void close() {
        OPEN.lock();
        try {
            this.lock.lock();
            try {
                if (this.closed) {
                    return;
                }
                this.closed = true;
                CURRENT.compareAndSet(this, null);
                ServiceStatus.deleteIfMatch(this.pidFile, this.pid, this.startedMillis);
                var join = this.bootstrap.join();
                if (join != null) {
                    join.wipeMemory();
                }
            } finally {
                this.lock.unlock();
            }
        } finally {
            OPEN.unlock();
        }
    }

    private void rewrite() throws IOException {
        var current = this.binding.get();
        String connection = current == null ? ConnectionState.PENDING.wire()
                : ConnectionState.canonical(current.connectionState());
        long version = 0;
        if (current != null) {
            long reported = current.configVersion();
            if (reported > 0) {
                version = reported;
            }
        }
        var dataRole = this.bootstrap.dataRole() == null ? "" : this.bootstrap.dataRole().wire();
        ServiceStatus.write(this.pidFile, this.pid, this.startedMillis, this.bootstrap.role().wire(), dataRole,
                connection, version, this.drainBudgetMs);
    }
}
