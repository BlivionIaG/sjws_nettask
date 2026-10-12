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
package sjws_nettask.ha;

import java.time.Duration;
import java.util.Objects;
import java.util.function.IntSupplier;
import sjws_nettask.core.Handler;

/**
 * Programmatic HA attachment. Flag parsing stays with the config workstream; this type is
 * the interface that workstream can fill in later.
 */
public final class HaSettings {

    private final Duration drainDelay;
    private final Duration drainTimeout;
    private final Sleeper sleeper;
    private final HaEvents events;
    private final MetricsSink metrics;
    private final boolean requireQuorum;
    private final Handler handler;
    private final IntSupplier acceptPause;

    private HaSettings(Duration drainDelay, Duration drainTimeout, Sleeper sleeper, HaEvents events,
            MetricsSink metrics, boolean requireQuorum, Handler handler, IntSupplier acceptPause) {
        this.drainDelay = drainDelay;
        this.drainTimeout = drainTimeout;
        this.sleeper = sleeper;
        this.events = events;
        this.metrics = metrics;
        this.requireQuorum = requireQuorum;
        this.handler = handler;
        this.acceptPause = acceptPause;
    }

    public static HaSettings defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Duration drainDelay() {
        return this.drainDelay;
    }

    public Duration drainTimeout() {
        return this.drainTimeout;
    }

    public Sleeper sleeper() {
        return this.sleeper;
    }

    public HaEvents events() {
        return this.events;
    }

    public MetricsSink metrics() {
        return this.metrics;
    }

    public boolean requireQuorum() {
        return this.requireQuorum;
    }

    /** @return override for the file handler, or null to use {@code StaticFileHandler} */
    public Handler handler() {
        return this.handler;
    }

    /** @return milliseconds to pause before accept, or null when active/active installs no gate */
    public IntSupplier acceptPause() {
        return this.acceptPause;
    }

    public static final class Builder {
        private Duration drainDelay = Duration.ofSeconds(5);
        private Duration drainTimeout = Duration.ofSeconds(15);
        private Sleeper sleeper = Sleeper.SYSTEM;
        private HaEvents events = HaEvents.NONE;
        private MetricsSink metrics = MetricsSink.NONE;
        private boolean requireQuorum;
        private Handler handler;
        private IntSupplier acceptPause;

        public Builder drainDelay(Duration drainDelay) {
            this.drainDelay = drainDelay;
            return this;
        }

        public Builder drainTimeout(Duration drainTimeout) {
            this.drainTimeout = drainTimeout;
            return this;
        }

        public Builder sleeper(Sleeper sleeper) {
            this.sleeper = sleeper;
            return this;
        }

        public Builder events(HaEvents events) {
            this.events = events;
            return this;
        }

        public Builder metrics(MetricsSink metrics) {
            this.metrics = metrics;
            return this;
        }

        public Builder requireQuorum(boolean requireQuorum) {
            this.requireQuorum = requireQuorum;
            return this;
        }

        public Builder handler(Handler handler) {
            this.handler = handler;
            return this;
        }

        public Builder acceptPause(IntSupplier acceptPause) {
            this.acceptPause = acceptPause;
            return this;
        }

        public HaSettings build() {
            Objects.requireNonNull(this.drainDelay, "drainDelay");
            Objects.requireNonNull(this.drainTimeout, "drainTimeout");
            Objects.requireNonNull(this.sleeper, "sleeper");
            Objects.requireNonNull(this.events, "events");
            Objects.requireNonNull(this.metrics, "metrics");
            if (this.drainDelay.isNegative() || this.drainTimeout.isNegative() || this.drainTimeout.isZero()) {
                throw new IllegalArgumentException("drainDelay must be >= 0 and drainTimeout must be > 0");
            }
            return new HaSettings(this.drainDelay, this.drainTimeout, this.sleeper, this.events, this.metrics,
                    this.requireQuorum, this.handler, this.acceptPause);
        }
    }
}
