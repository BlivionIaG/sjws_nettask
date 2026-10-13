package sjws_nettask.messaging.delivery;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Result of a batched fenced ack: one {@link LeaseResult} per input, in order, and the durability future of the
 * single ledger batch holding every accepted ACK (null when none was accepted).
 */
public record BatchAckResult(List<LeaseResult> results, CompletableFuture<Long> durable) { }
