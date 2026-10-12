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

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded, thread-safe ring of recent log lines, plus a helper to tail a log file. */
public final class LogBuffer {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final int capacity;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    public LogBuffer(int capacity) {
        this(capacity, Clock.systemDefaultZone());
    }

    LogBuffer(int capacity, Clock clock) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
        this.clock = clock;
    }

    /** Appends one line (multi-line text is split), prefixed with the local time. */
    public void add(String message) {
        var stamp = LocalTime.now(this.clock).format(TIME) + ' ';
        this.lock.lock();
        try {
            for (var line : String.valueOf(message).split("\\R", -1)) {
                if (this.lines.size() == this.capacity) {
                    this.lines.removeFirst();
                }
                this.lines.addLast(stamp + line);
            }
        } finally {
            this.lock.unlock();
        }
    }

    /** @return up to the last {@code n} lines, oldest first */
    public List<String> tail(int n) {
        this.lock.lock();
        try {
            var all = new ArrayList<>(this.lines);
            return List.copyOf(all.subList(Math.max(0, all.size() - Math.max(0, n)), all.size()));
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Last {@code n} lines of a text file, reading at most {@code maxBytes} from its end (so huge
     * logs are cheap). A partial first line caused by the byte limit is dropped.
     */
    public static List<String> tailFile(Path file, int n, int maxBytes) throws IOException {
        try (var raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            int toRead = (int) Math.min(length, maxBytes);
            var buf = new byte[toRead];
            raf.seek(length - toRead);
            raf.readFully(buf);
            var text = new String(buf, StandardCharsets.UTF_8);
            var all = new ArrayList<>(List.of(text.split("\\R", -1)));
            if (toRead < length) {
                all.remove(0);
            }
            if (!all.isEmpty() && all.get(all.size() - 1).isEmpty()) {
                all.remove(all.size() - 1);
            }
            return List.copyOf(all.subList(Math.max(0, all.size() - Math.max(0, n)), all.size()));
        }
    }
}
