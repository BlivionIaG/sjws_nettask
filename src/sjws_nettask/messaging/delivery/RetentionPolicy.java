package sjws_nettask.messaging.delivery;

/**
 * Log retention and compaction (docs/messaging/design.md §2.5, step 4). Nothing at or above the partition's
 * <em>low watermark</em> (the oldest message still needed by any group, or the oldest retained state snapshot) is ever
 * deleted, whatever the policy says.
 *
 * @param keepResolvedMs  a sealed segment wholly below the low watermark is deleted once it is older than this (0 = at once)
 * @param compactLiveRatio a sealed segment covered by a snapshot but pinned by a few live messages is rewritten to keep only
 *                         their ENQUEUE records when live bytes / segment bytes is below this ratio (0 = never compact)
 */
public record RetentionPolicy(long keepResolvedMs, double compactLiveRatio) {

    public static final RetentionPolicy DEFAULT = new RetentionPolicy(0, 0.5);

    public RetentionPolicy {
        if (keepResolvedMs < 0 || compactLiveRatio < 0 || compactLiveRatio > 1) throw new IllegalArgumentException("retention policy");
    }
}
