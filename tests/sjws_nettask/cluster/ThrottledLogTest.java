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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

/** #242 / #254: stack-trace throttle is time-based, per ThrottledLog instance, and never drops the first trace. */
class ThrottledLogTest {

    @Test
    void firstTraceOfATypeIsLoggedInFullThenOneLineUntilIntervalElapses() {
        long[] now = {0};
        var log = new ThrottledLog(System.getLogger("test.cluster.throttled"), Duration.ofSeconds(30), () -> now[0]);
        assertTrue(log.claim(IOException.class, now[0]), "first trace of a type is logged in full");
        assertFalse(log.claim(IOException.class, now[0] + 1), "within the interval: one-line only");
        assertTrue(log.claim(IllegalStateException.class, now[0] + 1), "other types are independent");
        now[0] += Duration.ofSeconds(30).toNanos();
        assertTrue(log.claim(IOException.class, now[0]), "full trace again after the interval");
    }

    @Test
    void separateInstancesDoNotShareThrottleState() {
        long[] now = {0};
        var a = new ThrottledLog(System.getLogger("test.cluster.throttled.a"), Duration.ofSeconds(30), () -> now[0]);
        var b = new ThrottledLog(System.getLogger("test.cluster.throttled.b"), Duration.ofSeconds(30), () -> now[0]);
        assertTrue(a.claim(IOException.class, now[0]));
        assertTrue(b.claim(IOException.class, now[0]), "a sibling ThrottledLog still gets the first full trace");
        assertFalse(a.claim(IOException.class, now[0] + 1));
        assertFalse(b.claim(IOException.class, now[0] + 1));
    }

    @Test
    void logEmitsFullThrowableOnFirstClaimAndRateLimitsAfter() {
        long[] now = {0};
        var records = new ArrayList<LogRecord>();
        var jul = Logger.getLogger("test.cluster.throttled.emit");
        var handler = capturing(records);
        var previous = jul.getLevel();
        jul.addHandler(handler);
        jul.setLevel(Level.ALL);
        try {
            var log = new ThrottledLog(System.getLogger(jul.getName()), Duration.ofSeconds(30), () -> now[0]);
            var err = new IOException("disk");
            log.log(System.Logger.Level.WARNING, "fsync failed (1 consecutive)", err);
            log.log(System.Logger.Level.WARNING, "fsync failed (2 consecutive)", err);
            assertTrue(records.stream().anyMatch(r -> r.getThrown() != null), "first log carries the stack");
            assertTrue(records.stream().anyMatch(r -> r.getThrown() == null
                    && r.getMessage() != null && r.getMessage().contains("stack trace rate-limited")),
                    "later log within the interval is one-line");
        } finally {
            jul.removeHandler(handler);
            jul.setLevel(previous);
        }
    }

    private static Handler capturing(List<LogRecord> records) {
        return new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }
}
