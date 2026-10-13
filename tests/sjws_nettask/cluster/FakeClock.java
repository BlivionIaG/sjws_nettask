package sjws_nettask.cluster;

import java.time.Duration;

/** Monotonic clock that only moves when a test says so. {@code sleep} advances it and returns. */
public final class FakeClock implements Clock {

    private long nanos;
    private long wallMillis;

    public FakeClock(long nanos) {
        this.nanos = nanos;
    }

    public void setWallMillis(long wallMillis) {
        this.wallMillis = wallMillis;
    }

    @Override
    public long currentTimeMillis() {
        return this.wallMillis;
    }

    @Override
    public long nanoTime() {
        return nanos;
    }

    public void advance(Duration duration) {
        nanos += duration.toNanos();
    }

    @Override
    public void sleep(Duration duration) {
        nanos += duration.toNanos();
    }
}
