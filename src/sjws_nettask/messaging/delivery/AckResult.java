package sjws_nettask.messaging.delivery;

import java.util.concurrent.CompletableFuture;

/** Result of a fenced ack: {@code durable} is the ACK record's durability future when {@code result} is OK, else null. */
public record AckResult(LeaseResult result, CompletableFuture<Long> durable) { }
