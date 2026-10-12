package sjws_nettask.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DateClockTest {

    final AtomicLong millis = new AtomicLong(Instant.parse("2026-10-12T01:02:03.100Z").toEpochMilli());
    final DateClock clock = new DateClock(InstantSource.fixed(Instant.EPOCH));
    final DateClock fake = new DateClock(new InstantSource() {
        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public long millis() {
            return millis.get();
        }
    });

    @Test
    void formatsOncePerSecond() {
        assertEquals("Mon, 12 Oct 2026 01:02:03 GMT", fake.now());
        for (int i = 0; i < 1000; i++) {
            millis.addAndGet(i % 2); // stays within the same second (…03.100 + 500 ms)
            assertEquals("Mon, 12 Oct 2026 01:02:03 GMT", fake.now());
        }
        assertEquals(1, fake.formats(), "no per-request formatting");
        millis.set(Instant.parse("2026-10-12T01:02:04Z").toEpochMilli());
        assertEquals("Mon, 12 Oct 2026 01:02:04 GMT", fake.now());
        assertEquals(2, fake.formats());
    }

    @Test
    void secondBoundaryIsExact() {
        millis.set(Instant.parse("2026-10-12T01:02:03.999Z").toEpochMilli());
        assertEquals("Mon, 12 Oct 2026 01:02:03 GMT", fake.now());
        millis.incrementAndGet();
        assertEquals("Mon, 12 Oct 2026 01:02:04 GMT", fake.now());
    }

    @Test
    void clockGoingBackIsFollowed() {
        fake.now();
        millis.addAndGet(-3600_000);
        assertEquals("Mon, 12 Oct 2026 00:02:03 GMT", fake.now());
    }

    @Test
    void beforeEpochAndEpoch() {
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", clock.now());
        millis.set(-1); // floorDiv: 1969-12-31T23:59:59
        assertEquals("Wed, 31 Dec 1969 23:59:59 GMT", fake.now());
    }

    @Test
    void systemClockIsCurrent() {
        var parsed = HttpDate.parse(DateClock.SYSTEM.now());
        assertTrue(Math.abs(parsed.getEpochSecond() - Instant.now().getEpochSecond()) <= 1);
    }

    @Test
    void concurrentCallersAgree() throws Exception {
        var threads = new Thread[8];
        var bad = new AtomicLong();
        for (int t = 0; t < threads.length; t++) {
            threads[t] = Thread.ofVirtual().start(() -> {
                for (int i = 0; i < 10_000; i++) {
                    if (!fake.now().startsWith("Mon, 12 Oct 2026 01:02:0")) {
                        bad.incrementAndGet();
                    }
                }
            });
        }
        for (int i = 0; i < 50; i++) {
            millis.addAndGet(20);
        }
        for (var t : threads) {
            t.join();
        }
        assertEquals(0, bad.get());
    }
}
