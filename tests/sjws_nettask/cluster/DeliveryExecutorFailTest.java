package sjws_nettask.cluster;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** #254: a failure handed to the delivery executor keeps its own error, also when the executor is closed. */
@Timeout(30)
class DeliveryExecutorFailTest {

    private static final GroupId G = new GroupId("msg/fail");

    @Test
    void failAfterCloseStillFailsWithTheGivenError() throws Exception {
        var d = new DeliveryExecutor("fail", 16);
        d.close(Duration.ofMillis(100));
        var future = new CompletableFuture<Void>();
        var unknown = new OutcomeUnknownException(G, "stopped leading", Optional.empty(), null);
        d.fail(future, unknown);
        var e = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertSame(unknown, e.getCause(), "not a generic 'group closed before delivery'");
    }

    @Test
    void failQueuedWhenTheExecutorClosesKeepsItsError() throws Exception {
        var d = new DeliveryExecutor("fail-queued", 16);
        var gate = new CompletableFuture<Void>();
        d.submit(gate::join, -1); // holds the delivery thread so the failure stays queued
        var future = new CompletableFuture<Void>();
        var unknown = new OutcomeUnknownException(G, "stopped leading", Optional.empty(), null);
        d.fail(future, unknown);
        d.close(Duration.ofMillis(50)); // abandons the queue, interrupts the running callback
        gate.complete(null);
        var e = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertSame(unknown, e.getCause());
    }
}
