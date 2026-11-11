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
package sjws_nettask.cp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.LongSupplier;
import sjws_nettask.config.ConfigDocument;
import sjws_nettask.config.NodeSettings;

/**
 * Data-role readiness: a verified last-good snapshot, the signed expiry clock, and an optional
 * operator {@code node.max_staleness} ceiling. Past max-staleness is not a drain.
 */
public final class DataPlaneGuard {

    /**
     * Production wall clock. Passing this to {@link #clock(LongSupplier)} keeps the monotonic
     * operator-interval deadline. Any other supplier is a test clock.
     */
    public static final LongSupplier WALL_CLOCK = System::currentTimeMillis;

    public static final DataPlaneGuard INACTIVE = new DataPlaneGuard(false, 0L, WALL_CLOCK);

    private final boolean active;
    private volatile LongSupplier clock;
    private volatile boolean injectedClock;
    private volatile long maxStalenessMs;
    private volatile boolean controlPlaneUp;
    private volatile boolean hasLastGood;
    private volatile long expiresAtMillis;
    private volatile long issuedAtMillis;
    /** Operator-interval deadline on {@link System#nanoTime()} when the wall clock is not injected. */
    private volatile long staleDeadlineNanos;

    private DataPlaneGuard(boolean active, long maxStalenessMs, LongSupplier clock) {
        this.active = active;
        this.maxStalenessMs = maxStalenessMs;
        this.clock = clock == null || clock == WALL_CLOCK ? WALL_CLOCK : clock;
        this.injectedClock = this.clock != WALL_CLOCK;
    }

    public static DataPlaneGuard inactive() {
        return INACTIVE;
    }

    /**
     * A live guard when the document is data mode. Loads a signed {@code last-good.json} from
     * {@code node.state_dir} when that file is present. Standalone and control stay
     * {@link #INACTIVE}.
     */
    public static DataPlaneGuard forDocument(ConfigDocument document) {
        if (document == null || document.node().mode() != NodeSettings.Mode.DATA) {
            return INACTIVE;
        }
        var max = document.node().maxStaleness();
        long ms = max == null ? 0L : max.toMillis();
        var guard = new DataPlaneGuard(true, ms, WALL_CLOCK);
        if (document.stateDir() != null) {
            guard.loadLastGood(document.stateDir(), null);
        }
        return guard;
    }

    /**
     * Like {@link #forDocument(ConfigDocument)}, then verifies a persisted last-good against
     * {@code pin} (the root public key, kept outside the state directory).
     */
    public static DataPlaneGuard forDocument(ConfigDocument document, Path pin) {
        var guard = forDocument(document);
        if (guard.active && document != null && document.stateDir() != null) {
            guard.loadLastGood(document.stateDir(), pin);
        }
        return guard;
    }

    public boolean active() {
        return this.active;
    }

    public void clock(LongSupplier clock) {
        if (!this.active) {
            return;
        }
        if (clock == null || clock == WALL_CLOCK) {
            this.clock = WALL_CLOCK;
            this.injectedClock = false;
            return;
        }
        this.clock = clock;
        this.injectedClock = true;
        refreshStaleDeadline();
    }

    /** Keep a process-local ceiling when a pushed snapshot does not name one. */
    public void maxStaleness(Duration duration) {
        if (!this.active) {
            return;
        }
        this.maxStalenessMs = duration == null ? 0L : duration.toMillis();
        refreshStaleDeadline();
    }

    public void controlPlane(boolean up) {
        if (this.active) {
            this.controlPlaneUp = up;
        }
    }

    public void loadLastGood(Path stateDir) {
        loadLastGood(stateDir, null);
    }

    /**
     * Accepts a persisted last-good only after signature, hash, expiry, and rollback checks
     * against the pinned trust material. A missing pin is fail-closed.
     */
    public void loadLastGood(Path stateDir, Path pin) {
        if (!this.active) {
            return;
        }
        var verified = DataApply.tryReadVerifiedLastGood(stateDir, pin).orElse(null);
        if (verified == null) {
            clearLastGood();
        }
    }

    /** Drops a previously accepted last-good when a later load does not verify. */
    public void clearLastGood() {
        if (!this.active) {
            return;
        }
        this.hasLastGood = false;
        this.expiresAtMillis = 0L;
        this.issuedAtMillis = 0L;
        this.staleDeadlineNanos = 0L;
    }

    public void applied(SignedSnapshot snapshot, boolean controlPlaneUp) {
        if (!this.active || snapshot == null) {
            return;
        }
        remember(snapshot);
        this.controlPlaneUp = controlPlaneUp;
    }

    private void remember(SignedSnapshot snapshot) {
        this.hasLastGood = snapshot.version() > 0;
        this.expiresAtMillis = snapshot.expiresAtMillis();
        this.issuedAtMillis = snapshot.issuedAtMillis();
        refreshStaleDeadline();
    }

    private void refreshStaleDeadline() {
        if (this.maxStalenessMs <= 0 || this.issuedAtMillis <= 0) {
            this.staleDeadlineNanos = 0L;
            return;
        }
        long remaining = this.issuedAtMillis + this.maxStalenessMs - this.clock.getAsLong();
        this.staleDeadlineNanos = remaining <= 0L ? System.nanoTime() : System.nanoTime() + remaining * 1_000_000L;
    }

    public boolean hasLastGood() {
        return this.hasLastGood;
    }

    public boolean pastMaxStaleness() {
        long now = this.clock.getAsLong();
        if (this.expiresAtMillis > 0 && now > this.expiresAtMillis) {
            return true;
        }
        if (this.maxStalenessMs <= 0 || this.issuedAtMillis <= 0) {
            return false;
        }
        if (this.injectedClock) {
            return NodeLifecycle.pastMaxStaleness(now, this.expiresAtMillis, this.issuedAtMillis, this.maxStalenessMs);
        }
        return this.staleDeadlineNanos > 0L && System.nanoTime() >= this.staleDeadlineNanos;
    }

    public NodeLifecycle.DataReadiness readiness() {
        if (!this.active) {
            return new NodeLifecycle.DataReadiness(true, true, false, false);
        }
        return NodeLifecycle.dataPlane(this.controlPlaneUp, this.hasLastGood, pastMaxStaleness());
    }

    public boolean ready() {
        return readiness().ready();
    }
}
