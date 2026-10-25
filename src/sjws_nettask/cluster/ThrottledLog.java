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

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-failure logging that cannot flood: the full stack trace at most once per interval per exception type, a
 * one-line message (type and message, no trace) otherwise. Used where every failed request or batch logs, so a
 * failing disk or a partition under load cannot fill the log with identical traces (#254).
 *
 * <p>Deliberately duplicated as {@code sjws_nettask.messaging.http.ThrottledLog}: there is no package both may import. {@code cluster} imports no
 * other sjws package, and {@code messaging} must not load consensus classes with HA off
 * (MessagingClusterIsolationTest). Keep the two copies identical.
 */
final class ThrottledLog {

    /** Distinct exception types tracked; beyond this, everything is one-line (bounded memory). */
    static final int MAX_TYPES = 64;

    private final System.Logger log;
    private final long intervalNanos;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<Class<?>, Long> lastTrace = new ConcurrentHashMap<>();

    ThrottledLog(System.Logger log, java.time.Duration interval) {
        this(log, interval, System::nanoTime);
    }

    ThrottledLog(System.Logger log, java.time.Duration interval, LongSupplier nanoClock) {
        this.log = log;
        this.intervalNanos = interval.toNanos();
        this.nanoClock = nanoClock;
    }

    /** Logs {@code message}; with {@code error}'s stack trace when this type has not had one within the interval. */
    void log(System.Logger.Level level, String message, Throwable error) {
        if (!log.isLoggable(level)) {
            return;
        }
        if (error == null) {
            log.log(level, message);
        } else if (claim(error.getClass(), nanoClock.getAsLong())) {
            log.log(level, message, error);
        } else {
            log.log(level, message + ": " + error + " (stack trace rate-limited)");
        }
    }

    /** True when the caller should log the full trace for {@code type} now (and records that it did). */
    boolean claim(Class<?> type, long now) {
        var won = new boolean[1];
        if (lastTrace.size() >= MAX_TYPES && !lastTrace.containsKey(type)) {
            return false;
        }
        lastTrace.compute(type, (k, prev) -> {
            if (prev == null || now - prev >= intervalNanos) {
                won[0] = true;
                return now;
            }
            return prev;
        });
        return won[0];
    }
}
