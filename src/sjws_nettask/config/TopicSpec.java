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
package sjws_nettask.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Desired state of one topic. Plain data: the control plane stores it and does not open a
 * partition. Durability {@code none} and {@code each} are not topic defaults; they belong only
 * in {@link #durabilityOptIn()}.
 */
public record TopicSpec(int partitions, int replication, String durability, List<String> durabilityOptIn,
        int maxAttempts, Duration backoffBase, Duration backoffMax, boolean dlqEnabled, String dlqSuffix,
        Duration dlqRetention, boolean keepResolved, double compactLiveRatio, Duration retentionMaxAge,
        long retentionMaxBytes, long segmentBytes, Duration visibilityTimeout, Map<String, String> brokerSelector,
        String spreadBy, Map<String, GroupSpec> groups, boolean allowDestructive) {

    public TopicSpec {
        durabilityOptIn = List.copyOf(durabilityOptIn);
        brokerSelector = Map.copyOf(brokerSelector);
        groups = Map.copyOf(groups);
        if (dlqSuffix == null) {
            dlqSuffix = ".dlq";
        }
        if (spreadBy == null) {
            spreadBy = "";
        }
    }
}
