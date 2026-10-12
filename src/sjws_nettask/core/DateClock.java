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
package sjws_nettask.core;

import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.atomic.LongAdder;

/**
 * The value of the {@code Date} response field, formatted at most once per second (the field has
 * one-second resolution) instead of once per response. Lock-free: a racing refresh formats the
 * same string twice, which is harmless.
 */
public final class DateClock {

    /** Shared by the writers of every transport. */
    public static final DateClock SYSTEM = new DateClock(InstantSource.system());

    private record Snapshot(long epochSecond, String text) {
    }

    private final InstantSource source;
    private final LongAdder formats = new LongAdder();
    private volatile Snapshot current;

    /** @param source time source (tests inject a fake one) */
    public DateClock(InstantSource source) {
        this.source = source;
    }

    /** @return the current time as an HTTP date (IMF-fixdate) */
    public String now() {
        long second = Math.floorDiv(this.source.millis(), 1000L);
        var snapshot = this.current;
        if (snapshot == null || snapshot.epochSecond() != second) { // also after the clock went back
            snapshot = new Snapshot(second, HttpDate.format(Instant.ofEpochSecond(second)));
            this.formats.increment();
            this.current = snapshot;
        }
        return snapshot.text();
    }

    /** @return how many times the date was formatted (tests) */
    public long formats() {
        return this.formats.sum();
    }
}
