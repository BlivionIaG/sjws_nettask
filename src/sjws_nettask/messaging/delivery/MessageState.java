package sjws_nettask.messaging.delivery;

/** Per-consumer-group state of one message (docs/messaging/design.md §1). */
public enum MessageState { ENQUEUED, IN_FLIGHT, ACKED, DEAD_LETTERED }
