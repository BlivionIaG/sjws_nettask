package sjws_nettask.messaging.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.core.Response;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.LeaderUnavailableException;
import sjws_nettask.messaging.ledger.LedgerWriter;
import sjws_nettask.messaging.ledger.PartitionReplicator;
import sjws_nettask.messaging.ledger.Replica;
import sjws_nettask.messaging.ledger.WriteRefusedException;

/**
 * #254 through the real produce handler, with a stub replica: refused (307 / not-appended) versus outcome unknown
 * (typed 503 with a leader hint, never a 307), including the ack timeout.
 */
@Timeout(30)
class MessagingHttpOutcomeUnknownTest {

    @TempDir Path dir;
    LogTopics topics;

    /** The leader of term 4, named n1; every append answers with {@code next}. */
    static final class StubReplica implements Replica {
        final Supplier<CompletableFuture<Long>> next;

        StubReplica(Supplier<CompletableFuture<Long>> next) {
            this.next = next;
        }

        @Override public boolean leaderReady() { return true; }
        @Override public long term() { return 4; }
        @Override public String leaderName() { return "n1"; }
        @Override public String leaderUrl() { return null; }
        @Override public CompletableFuture<Void> leaderRead() { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Long> append(List<byte[]> records, Durability mode) { return next.get(); }
        @Override public CompletableFuture<Long> appendNoWait(List<byte[]> records, Durability mode) { return next.get(); }
        @Override public void awaitCapacity() { }
        @Override public void close() { }
    }

    MessagingHttp http(Supplier<CompletableFuture<Long>> next) throws Exception {
        topics = new LogTopics(dir, Durability.ASYNC);
        topics.replication(new PartitionReplicator() {
            @Override public Set<String> topics() { return Set.of("t"); }
            @Override public Replica open(String topic, LedgerWriter local, Durability mode, Consumer<List<byte[]>> applied) {
                return new StubReplica(next);
            }
            @Override public void close() { }
        });
        return new MessagingHttp(topics);
    }

    @AfterEach
    void close() {
        if (topics != null) topics.close();
    }

    static Response produce(MessagingHttp h) {
        return h.handle(MessagingReplicationTest.post("t", "x", Map.of()));
    }

    /** An ack that never arrives in time: the append may still commit, so typed 503, no Location (#254). */
    @Test
    void produceTimeoutIsATypedOutcomeUnknown503NotARedirect() throws Exception {
        var r = produce(http(() -> new CompletableFuture<>() {
            @Override
            public Long get(long timeout, TimeUnit unit) throws TimeoutException {
                throw new TimeoutException("stub: no ack");
            }
        }));
        var body = LogOffsetsTest.text(r);
        assertEquals(503, r.status(), body);
        assertNull(r.headers().get("location"), body);
        assertEquals(MessagingHttp.RETRY_AFTER_SECONDS, r.headers().get("retry-after"));
        assertEquals("application/problem+json", r.headers().get("content-type"));
        assertTrue(body.contains("\"type\":\"" + MessagingHttp.OUTCOME_UNKNOWN_TYPE + "\""), body);
        assertEquals("4", r.headers().get("sjws-term"));
        assertEquals("n1", r.headers().get("sjws-leader"), "hint: this node still leads; read back here");
    }

    @Test
    void notLeaderRefusalStillRedirects() throws Exception {
        var r = produce(http(() -> CompletableFuture.failedFuture(
                new LeaderUnavailableException("not leader", 5, "n2", "http://n2.test"))));
        assertEquals(307, r.status(), LogOffsetsTest.text(r));
        assertEquals("http://n2.test/messaging/v1/topics/t/records", r.headers().get("location"));
    }

    @Test
    void refusedBeforeTheLogIsNotAppendedNotOutcomeUnknown() throws Exception {
        var r = produce(http(() -> CompletableFuture.failedFuture(
                new WriteRefusedException("t: not appended: apply lag /secret", true, null))));
        var body = LogOffsetsTest.text(r);
        assertEquals(503, r.status(), body);
        assertEquals(MessagingHttp.RETRY_AFTER_SECONDS, r.headers().get("retry-after"));
        assertTrue(body.contains(MessagingHttp.NOT_APPENDED_TYPE), body);
        assertFalse(body.contains(MessagingHttp.OUTCOME_UNKNOWN_TYPE) || body.contains("secret"), body);
        assertNull(r.headers().get("location"));
    }

    @Test
    void anUnacceptableBatchIsA400NotAppended() throws Exception {
        var r = produce(http(() -> CompletableFuture.failedFuture(
                new WriteRefusedException("t: not appended: batch exceeds maxBatchBytes", false, null))));
        var body = LogOffsetsTest.text(r);
        assertEquals(400, r.status(), body);
        assertTrue(body.contains(MessagingHttp.NOT_APPENDED_TYPE), body);
        assertNull(r.headers().get("retry-after"));
    }
}
