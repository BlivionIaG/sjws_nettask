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

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.LedgerWriter;
import sjws_nettask.messaging.ledger.SegmentedLog;

/**
 * Plain log topics (docs/design/partition-log.md R1, R9): one {@link SegmentedLog} plus its single
 * {@link LedgerWriter} per topic, under {@code dir/<topic>}, opened on first produce. No delivery groups.
 */
final class LogTopics implements AutoCloseable {

    static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    /** {@code reads} serializes fetches: segment readers keep a sequential-read hint and chunk cache. */
    record Log(SegmentedLog log, LedgerWriter writer, java.util.concurrent.locks.ReentrantLock reads) { }

    private final Path dir;
    private final Durability durability;
    private final ConcurrentHashMap<String, Log> logs = new ConcurrentHashMap<>();
    private final java.util.concurrent.locks.ReentrantLock opening = new java.util.concurrent.locks.ReentrantLock();
    private volatile boolean closed;

    LogTopics(Path dir, Durability durability) {
        this.dir = dir;
        this.durability = durability;
    }

    static boolean validName(String topic) {
        return NAME.matcher(topic).matches() && !topic.equals(".") && !topic.equals("..");
    }

    /** The topic's log, opening (and creating) it when {@code create}; null when absent and not created. */
    Log get(String topic, boolean create) throws IOException {
        if (this.closed) throw new IllegalStateException("closed");
        var l = this.logs.get(topic);
        if (l != null || (!create && !java.nio.file.Files.isDirectory(this.dir.resolve(topic)))) return l;
        this.opening.lock(); // opens do file I/O, so not inside computeIfAbsent (design review M12)
        try {
            if (this.closed) throw new IllegalStateException("closed");
            l = this.logs.get(topic);
            if (l == null) {
                var log = SegmentedLog.open(this.dir.resolve(topic));
                l = new Log(log, new LedgerWriter(log, this.durability), new java.util.concurrent.locks.ReentrantLock());
                this.logs.put(topic, l);
            }
            return l;
        } finally {
            this.opening.unlock();
        }
    }

    @Override
    public void close() {
        this.opening.lock();
        try {
            this.closed = true;
        } finally {
            this.opening.unlock();
        }
        for (var l : this.logs.values()) {
            try {
                l.writer().close();
                l.log().close();
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                System.err.println("WARN: closing log: " + e);
            }
        }
        this.logs.clear();
    }
}
