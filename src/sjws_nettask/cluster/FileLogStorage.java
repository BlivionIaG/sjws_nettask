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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One segment file per group. {@link #force} calls {@link FileChannel#force}. A truncate rewrites
 * the segment from the in-memory index; the hot path only appends.
 */
public final class FileLogStorage implements LogStorage {

    private final Path root;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<GroupId, NavigableMap<Long, Rec>> logs = new ConcurrentHashMap<>();
    private final Map<GroupId, FileChannel> channels = new ConcurrentHashMap<>();
    private final Map<GroupId, AtomicInteger> forceCounts = new ConcurrentHashMap<>();

    public FileLogStorage(Path root) throws IOException {
        this.root = root;
        Files.createDirectories(root);
    }

    private NavigableMap<Long, Rec> log(GroupId group) throws IOException {
        var existing = logs.get(group);
        if (existing != null) {
            return existing;
        }
        lock.lock();
        try {
            existing = logs.get(group);
            if (existing != null) {
                return existing;
            }
            var loaded = new ConcurrentSkipListMap<Long, Rec>();
            var path = path(group);
            if (Files.exists(path) && Files.size(path) > 0) {
                try (var in = FileChannel.open(path, StandardOpenOption.READ)) {
                    var header = ByteBuffer.allocate(20);
                    while (in.position() < in.size()) {
                        header.clear();
                        if (in.read(header) != 20) {
                            break;
                        }
                        header.flip();
                        long index = header.getLong();
                        long term = header.getLong();
                        int len = header.getInt();
                        var data = new byte[len];
                        var body = ByteBuffer.wrap(data);
                        while (body.hasRemaining()) {
                            if (in.read(body) < 0) {
                                break;
                            }
                        }
                        loaded.put(index, new Rec(term, data));
                    }
                }
            }
            channels.put(group, FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE));
            logs.put(group, loaded);
            return loaded;
        } finally {
            lock.unlock();
        }
    }

    private Path path(GroupId group) {
        var safe = group.value().replaceAll("[^A-Za-z0-9._-]", "_");
        return root.resolve(safe + ".log");
    }

    @Override
    public void append(GroupId group, long index, long term, byte[] data) throws IOException {
        if (index < 1) {
            throw new IllegalArgumentException("index");
        }
        var copy = data.clone();
        var log = log(group);
        lock.lock();
        try {
            var channel = channels.get(group);
            channel.position(channel.size());
            var header = ByteBuffer.allocate(20 + copy.length);
            header.putLong(index);
            header.putLong(term);
            header.putInt(copy.length);
            header.put(copy);
            header.flip();
            while (header.hasRemaining()) {
                channel.write(header);
            }
            log.put(index, new Rec(term, copy));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public byte[] read(GroupId group, long index) throws IOException {
        var rec = log(group).get(index);
        return rec == null ? null : rec.data.clone();
    }

    @Override
    public long termAt(GroupId group, long index) throws IOException {
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
    public void truncateSuffix(GroupId group, long fromIndex) throws IOException {
        rewrite(group, log(group).headMap(fromIndex, false));
    }

    @Override
    public void truncatePrefix(GroupId group, long throughIndex) throws IOException {
        rewrite(group, log(group).tailMap(throughIndex, false));
    }

    private void rewrite(GroupId group, NavigableMap<Long, Rec> keep) throws IOException {
        lock.lock();
        try {
            var fresh = new ConcurrentSkipListMap<Long, Rec>();
            var channel = channels.get(group);
            channel.truncate(0);
            channel.position(0);
            for (var entry : keep.entrySet()) {
                var rec = entry.getValue();
                var header = ByteBuffer.allocate(20 + rec.data.length);
                header.putLong(entry.getKey());
                header.putLong(rec.term);
                header.putInt(rec.data.length);
                header.put(rec.data);
                header.flip();
                while (header.hasRemaining()) {
                    channel.write(header);
                }
                fresh.put(entry.getKey(), rec);
            }
            logs.put(group, fresh);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void force(GroupId group) throws IOException {
        log(group);
        lock.lock();
        try {
            channels.get(group).force(true);
            var count = forceCounts.get(group);
            if (count == null) {
                var created = new AtomicInteger();
                var raced = forceCounts.putIfAbsent(group, created);
                count = raced == null ? created : raced;
            }
            count.incrementAndGet();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int forces(GroupId group) {
        var count = forceCounts.get(group);
        return count == null ? 0 : count.get();
    }

    public void close() throws IOException {
        lock.lock();
        try {
            for (var channel : channels.values()) {
                channel.close();
            }
            channels.clear();
        } finally {
            lock.unlock();
        }
    }

    private record Rec(long term, byte[] data) {
    }
}
