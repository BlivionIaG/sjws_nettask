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
package sjws_nettask.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import sjws_nettask.messaging.delivery.DeliverySnapshot;
import sjws_nettask.messaging.delivery.LedgerInspector;
import sjws_nettask.messaging.delivery.MessageState;

/**
 * One consumer group of one topic as the admin Queues view shows it (#384). Built from
 * {@link LedgerInspector} snapshots only: counts are the lock-free exact counters and lists come
 * from the rate-limited snapshot, so a read never holds a partition lock across the admin call.
 * Payloads are never read.
 */
public record QueueView(String topic, String group, long pending, long inFlight, long deadLettered,
        long oldestPendingAgeMs, boolean truncated) {

    /** A dead-lettered entry without its payload. */
    public record Entry(long offset, int attempts, String consumer, long enqueuedAtMs, int size, String reason) {
        static Entry of(DeliverySnapshot.Item item) {
            return new Entry(item.offset(), item.attempts(), item.consumer(), item.enqueuedAtMs(), item.size(),
                    item.reason());
        }
    }

    /** Maximum DLQ entries per page. */
    public static final int MAX_PAGE = 100;

    /** Every group of every topic, in the map's order. {@code nowMs} dates the oldest pending entry. */
    public static List<QueueView> read(Map<String, LedgerInspector> topics, long nowMs) {
        var out = new ArrayList<QueueView>();
        topics.forEach((topic, inspector) -> {
            var snapshot = inspector.snapshot();
            inspector.counts().forEach((group, counts) -> {
                var view = snapshot.groups().get(group);
                long oldest = -1;
                boolean truncated = false;
                if (view != null) {
                    truncated = view.truncated();
                    if (!view.enqueued().isEmpty()) {
                        long at = view.enqueued().get(0).enqueuedAtMs();
                        oldest = at > 0 ? Math.max(0, nowMs - at) : -1;
                    }
                }
                out.add(new QueueView(topic, group, counts.enqueued(), counts.inFlight(), counts.deadLettered(),
                        oldest, truncated));
            });
        });
        return out;
    }

    /** One page of dead-lettered entries of {@code group}, offset {@code >= fromOffset}, at most {@link #MAX_PAGE}. */
    public static List<Entry> deadLetters(LedgerInspector inspector, String group, long fromOffset, int limit) {
        int n = Math.max(0, Math.min(limit, MAX_PAGE));
        return inspector.page(group, MessageState.DEAD_LETTERED, fromOffset, n).stream().map(Entry::of).toList();
    }
}
