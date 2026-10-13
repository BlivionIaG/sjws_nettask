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
package sjws_nettask.messaging.http;

/**
 * Size and age retention for plain log topics (docs/design/partition-log.md R6, §2.3, gap G2). Only whole sealed
 * segments are deleted, oldest first, so the log stays a suffix and fetch below the new start gets 416. The active
 * segment is never deleted, so a topic may exceed {@code maxBytes} by up to one segment.
 *
 * @param maxBytes delete the oldest sealed segments while the topic is larger than this (0 = no size limit)
 * @param maxAgeMs delete sealed segments last written more than this long ago (0 = no age limit)
 */
public record LogRetention(long maxBytes, long maxAgeMs) {

    public static final LogRetention NONE = new LogRetention(0, 0);

    public LogRetention {
        if (maxBytes < 0 || maxAgeMs < 0) throw new IllegalArgumentException("retention limits must be >= 0");
    }

    boolean unlimited() {
        return maxBytes == 0 && maxAgeMs == 0;
    }

    /** Whether the oldest sealed segment goes: the topic is over its size limit, or the segment is past its age. */
    boolean expired(long topicBytes, long segmentLastModifiedMs, long nowMs) {
        return (maxBytes > 0 && topicBytes > maxBytes) || (maxAgeMs > 0 && nowMs - segmentLastModifiedMs >= maxAgeMs);
    }
}
