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

import java.util.concurrent.TimeUnit;

/**
 * Backoff for the watch-tick content-bind retry: the delay doubles from {@link #MIN_NANOS} up to
 * {@link #MAX_NANOS} after each failure and resets on success, so a control plane that answers
 * 204 at once cannot drive a tight bind loop. A repeated identical failure is logged once.
 * Used from the watch thread only.
 */
final class BindRetry {

    static final long MIN_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    static final long MAX_NANOS = TimeUnit.SECONDS.toNanos(30);

    private long delayNanos;
    private long notBeforeNanos;
    private String lastWarning;

    /** True when no failure is pending or its delay has passed at {@code nowNanos}. */
    boolean due(long nowNanos) {
        return this.delayNanos == 0L || nowNanos - this.notBeforeNanos >= 0L;
    }

    /** Records a failed attempt; returns true when {@code message} differs from the last one logged. */
    boolean failed(long nowNanos, String message) {
        this.delayNanos = this.delayNanos == 0L ? MIN_NANOS : Math.min(MAX_NANOS, this.delayNanos * 2);
        this.notBeforeNanos = nowNanos + this.delayNanos;
        if (message.equals(this.lastWarning)) {
            return false;
        }
        this.lastWarning = message;
        return true;
    }

    void succeeded() {
        this.delayNanos = 0L;
        this.lastWarning = null;
    }

    long delayNanos() {
        return this.delayNanos;
    }
}
