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
package sjws_nettask.controlplane;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Append-only, hash-chained JSONL audit trail. Each line is
 * <pre>{"seq":N,"ts":"ISO-8601","actor":"..","action":"..","target":"..","outcome":"..","version":V,"prev":"HEX","hash":"HEX"}</pre>
 * where {@code hash = SHA-256(line without its ,"hash":".." member)} and {@code prev} is the
 * previous line's hash (64 zeros for the first). Editing, inserting, deleting or reordering lines
 * breaks the chain and {@link #verify} reports where. Dropping lines from the end is only
 * detectable against an anchored {@link #head()} (e.g. replicated in the Raft log), so callers
 * should anchor it on every config commit.
 * <p>
 * Opening an existing file verifies it first and refuses to extend a broken chain.
 */
public final class AuditLog implements AutoCloseable {

    /** Longer actor/target/outcome values (they can come from a caller) are cut. */
    static final int MAX_FIELD = 256;
    static final String GENESIS = "0".repeat(64);
    private static final Pattern LINE = Pattern.compile(
            "\\{\"seq\":(\\d+),\"ts\":\"[^\"]*\",.*,\"prev\":\"([0-9a-f]{64})\",\"hash\":\"([0-9a-f]{64})\"\\}");

    /** Where the chain is: the last sequence number and hash. */
    public record Head(long seq, String hash) {
    }

    /** Result of {@link #verify}: {@code badLine} is 0 when the chain is intact. */
    public record Verification(long entries, String head, int badLine, String problem) {
        public boolean ok() {
            return this.badLine == 0;
        }
    }

    private final FileChannel out;
    private final Clock clock;
    private final boolean durable;
    private final ReentrantLock lock = new ReentrantLock();
    private long seq;
    private String head;

    /**
     * @param durable fsync each entry (recommended; entries are rare)
     * @throws IOException if the file can't be opened, or its chain is broken
     */
    public AuditLog(Path file, Clock clock, boolean durable) throws IOException {
        this.clock = clock;
        this.durable = durable;
        if (Files.exists(file)) {
            var v = verify(file);
            if (!v.ok()) {
                throw new IOException("audit log " + file + " is broken at line " + v.badLine() + ": " + v.problem());
            }
            this.seq = v.entries();
            this.head = v.head();
        } else {
            this.seq = 0;
            this.head = GENESIS;
        }
        this.out = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
    }

    public Head head() {
        this.lock.lock();
        try {
            return new Head(this.seq, this.head);
        } finally {
            this.lock.unlock();
        }
    }

    /** Appends one entry. @return its hash */
    public String append(String actor, String action, String target, String outcome, long version)
            throws IOException {
        this.lock.lock();
        try {
            long n = this.seq + 1;
            var body = new StringBuilder(256).append("{\"seq\":").append(n)
                    .append(",\"ts\":\"").append(this.clock.instant()).append('"');
            member(body, "actor", actor);
            member(body, "action", action);
            member(body, "target", target);
            member(body, "outcome", outcome);
            body.append(",\"version\":").append(version).append(",\"prev\":\"").append(this.head).append('"');
            var hash = AccessPolicyDocument.sha256(body + "}");
            body.append(",\"hash\":\"").append(hash).append("\"}\n");
            var buf = ByteBuffer.wrap(body.toString().getBytes(StandardCharsets.UTF_8));
            while (buf.hasRemaining()) {
                this.out.write(buf);
            }
            if (this.durable) {
                this.out.force(false);
            }
            this.seq = n;
            this.head = hash;
            return hash;
        } finally {
            this.lock.unlock();
        }
    }

    /** Re-hashes every line of a log. Never throws on bad content; reports the first broken line. */
    public static Verification verify(Path file) throws IOException {
        var prev = GENESIS;
        long n = 0;
        int lineNo = 0;
        var text = Files.readString(file, StandardCharsets.UTF_8);
        if (!text.isEmpty() && !text.endsWith("\n")) {
            return new Verification(n, prev, text.split("\n", -1).length, "torn last line");
        }
        for (var line : text.split("\n")) {
            if (line.isEmpty() && text.isEmpty()) {
                break;
            }
            lineNo++;
            var m = LINE.matcher(line);
            if (!m.matches()) {
                return new Verification(n, prev, lineNo, "malformed entry");
            }
            if (Long.parseLong(m.group(1)) != n + 1) {
                return new Verification(n, prev, lineNo, "sequence gap");
            }
            if (!m.group(2).equals(prev)) {
                return new Verification(n, prev, lineNo, "prev does not match the previous hash");
            }
            var body = line.substring(0, line.length() - ",\"hash\":\"\"}".length() - 64) + "}";
            if (!AccessPolicyDocument.sha256(body).equals(m.group(3))) {
                return new Verification(n, prev, lineNo, "hash mismatch (entry edited)");
            }
            prev = m.group(3);
            n++;
        }
        return new Verification(n, prev, 0, null);
    }

    private static void member(StringBuilder b, String name, String value) {
        b.append(",\"").append(name).append("\":\"");
        var v = value == null ? "-" : value.length() > MAX_FIELD ? value.substring(0, MAX_FIELD) + "..." : value;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                default -> {
                    if (c < 0x20 || c == 0x7f || (c >= 0x2028 && c <= 0x2029)) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }

    @Override
    public void close() throws IOException {
        this.lock.lock();
        try {
            this.out.close();
        } finally {
            this.lock.unlock();
        }
    }
}
