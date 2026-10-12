package sjws_nettask.acme;

import java.util.concurrent.TimeUnit;

/**
 * Joins the ACME renew thread before a temp directory is deleted. {@code AcmeService.close}
 * only calls {@code shutdownNow}, so the in-flight attempt can still create the store (an RSA
 * key, then {@code .acme-*.tmp}) after the test method returns. JUnit's temp-dir walk then
 * deletes the root and fails with {@code DirectoryNotEmptyException}.
 */
public final class AcmeCleanup {

    private static final String RENEW_THREAD = "acme-renew";

    private AcmeCleanup() {
    }

    public static void awaitWriters() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (true) {
            var live = Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> thread.isAlive() && RENEW_THREAD.equals(thread.getName()))
                    .toList();
            if (live.isEmpty()) {
                return;
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(RENEW_THREAD + " still running after close");
            }
            for (var thread : live) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                thread.join(TimeUnit.NANOSECONDS.toMillis(remaining));
            }
        }
    }
}
