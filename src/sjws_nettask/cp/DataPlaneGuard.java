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

    public static final DataPlaneGuard INACTIVE = new DataPlaneGuard(false, 0L, System::currentTimeMillis);

    private final boolean active;
    private volatile LongSupplier clock;
    private volatile long maxStalenessMs;
    private volatile boolean controlPlaneUp;
    private volatile boolean hasLastGood;
    private volatile long expiresAtMillis;
    private volatile long issuedAtMillis;

    private DataPlaneGuard(boolean active, long maxStalenessMs, LongSupplier clock) {
        this.active = active;
        this.maxStalenessMs = maxStalenessMs;
        this.clock = clock == null ? System::currentTimeMillis : clock;
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
        var guard = new DataPlaneGuard(true, ms, System::currentTimeMillis);
        if (document.stateDir() != null) {
            guard.loadLastGood(document.stateDir());
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
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /** Keep a process-local ceiling when a pushed snapshot does not name one. */
    public void maxStaleness(Duration duration) {
        if (!this.active) {
            return;
        }
        this.maxStalenessMs = duration == null ? 0L : duration.toMillis();
    }

    public void controlPlane(boolean up) {
        if (this.active) {
            this.controlPlaneUp = up;
        }
    }

    public void loadLastGood(Path stateDir) {
        if (!this.active) {
            return;
        }
        var snapshot = DataApply.tryReadLastGood(stateDir).orElse(null);
        if (snapshot != null) {
            remember(snapshot);
        }
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
    }

    public boolean hasLastGood() {
        return this.hasLastGood;
    }

    public boolean pastMaxStaleness() {
        return NodeLifecycle.pastMaxStaleness(this.clock.getAsLong(), this.expiresAtMillis, this.issuedAtMillis,
                this.maxStalenessMs);
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
