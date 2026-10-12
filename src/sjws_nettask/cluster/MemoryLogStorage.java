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
package sjws_nettask.cluster;

import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory log. {@link #force} only counts; it does not touch a disk. */
public final class MemoryLogStorage implements LogStorage {

    private final Map<GroupId, NavigableMap<Long, Rec>> logs = new ConcurrentHashMap<>();
    private final Map<GroupId, AtomicInteger> forceCounts = new ConcurrentHashMap<>();

    private NavigableMap<Long, Rec> log(GroupId group) {
        var existing = logs.get(group);
        if (existing != null) {
            return existing;
        }
        var created = new ConcurrentSkipListMap<Long, Rec>();
        var raced = logs.putIfAbsent(group, created);
        return raced == null ? created : raced;
    }

    @Override
    public void append(GroupId group, long index, long term, byte[] data) {
        if (index < 1) {
            throw new IllegalArgumentException("index");
        }
        log(group).put(index, new Rec(term, data.clone()));
    }

    @Override
    public byte[] read(GroupId group, long index) {
        var rec = log(group).get(index);
        return rec == null ? null : rec.data.clone();
    }

    @Override
    public long termAt(GroupId group, long index) {
        var rec = log(group).get(index);
        return rec == null ? 0L : rec.term;
    }

    @Override
    public long lastIndex(GroupId group) {
        var log = logs.get(group);
        if (log == null || log.isEmpty()) {
            return 0L;
        }
        return log.lastKey();
    }

    @Override
    public void truncateSuffix(GroupId group, long fromIndex) {
        var log = log(group);
        log.tailMap(fromIndex).clear();
    }

    @Override
    public void truncatePrefix(GroupId group, long throughIndex) {
        var log = log(group);
        log.headMap(throughIndex, true).clear();
    }

    @Override
    public void force(GroupId group) {
        forces(group, true);
    }

    @Override
    public int forces(GroupId group) {
        return forces(group, false);
    }

    private int forces(GroupId group, boolean increment) {
        var count = forceCounts.get(group);
        if (count == null) {
            if (!increment) {
                return 0;
            }
            var created = new AtomicInteger();
            var raced = forceCounts.putIfAbsent(group, created);
            count = raced == null ? created : raced;
        }
        return increment ? count.incrementAndGet() : count.get();
    }

    private record Rec(long term, byte[] data) {
    }
}
