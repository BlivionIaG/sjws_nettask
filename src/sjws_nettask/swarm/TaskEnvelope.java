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
package sjws_nettask.swarm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * The task and reply envelope carried in the message payload until messaging has headers
 * (docs/design/agent-swarm.md §4.5). Layout, version 1, big-endian:
 * {@code [version:u8][taskId:16][correlationId:16][deadlineMs:i64][replyTo][traceparent][tracestate][budgetRef]
 * [contentType][bodyLen:i32][body]}, where each text field is {@code [len:u16][UTF-8]}.
 */
public record TaskEnvelope(byte[] taskId, byte[] correlationId, String replyTo, long deadlineMs, String traceparent,
        String tracestate, String budgetRef, String contentType, byte[] body) {

    public static final int VERSION = 1;
    /** Inline body limit (§5.2, Messaging Core): larger results go to the swarm-owned blob store by reference. */
    public static final int MAX_INLINE_BODY = 1 << 20;
    /** No deadline. */
    public static final long NO_DEADLINE = Long.MAX_VALUE;

    public TaskEnvelope {
        if (taskId.length != 16 || correlationId.length != 16) {
            throw new IllegalArgumentException("taskId and correlationId are 16 bytes");
        }
        if (body.length > MAX_INLINE_BODY) {
            throw new IllegalArgumentException("body larger than " + MAX_INLINE_BODY + " bytes; store it by reference");
        }
        taskId = taskId.clone();
        correlationId = correlationId.clone();
        replyTo = Objects.requireNonNullElse(replyTo, "");
        traceparent = Objects.requireNonNullElse(traceparent, "");
        tracestate = Objects.requireNonNullElse(tracestate, "");
        budgetRef = Objects.requireNonNullElse(budgetRef, "");
        contentType = Objects.requireNonNullElse(contentType, "");
        body = body.clone();
    }

    @Override
    public byte[] taskId() {
        return this.taskId.clone();
    }

    @Override
    public byte[] correlationId() {
        return this.correlationId.clone();
    }

    @Override
    public byte[] body() {
        return this.body.clone();
    }

    /** True when the caller's deadline is set and has passed at {@code nowMs}. */
    public boolean expiredAt(long nowMs) {
        return this.deadlineMs != NO_DEADLINE && nowMs >= this.deadlineMs;
    }

    /** The reply to this task: same ids, empty {@code replyTo}, the given body. */
    public TaskEnvelope reply(String contentType, byte[] body) {
        return new TaskEnvelope(this.taskId, this.correlationId, "", this.deadlineMs, this.traceparent, this.tracestate,
                this.budgetRef, contentType, body);
    }

    public byte[] encode() {
        var fast = encodeAscii();
        if (fast != null) return fast;
        var buf = new ByteArrayOutputStream(64 + this.body.length);
        try (var out = new DataOutputStream(buf)) {
            out.writeByte(VERSION);
            out.write(this.taskId);
            out.write(this.correlationId);
            out.writeLong(this.deadlineMs);
            for (var s : new String[] {this.replyTo, this.traceparent, this.tracestate, this.budgetRef, this.contentType}) {
                out.writeUTF(s);
            }
            out.writeInt(this.body.length);
            out.write(this.body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buf.toByteArray();
    }

    /** Decodes {@link #encode()}; a wrong version or a truncated payload is an {@link IllegalArgumentException}. */
    public static TaskEnvelope decode(byte[] payload) {
        var fast = decodeAscii(payload);
        if (fast != null) return fast;
        try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int version = in.readUnsignedByte();
            if (version != VERSION) {
                throw new IllegalArgumentException("envelope version " + version);
            }
            var taskId = in.readNBytes(16);
            var correlationId = in.readNBytes(16);
            long deadline = in.readLong();
            var replyTo = in.readUTF();
            var traceparent = in.readUTF();
            var tracestate = in.readUTF();
            var budgetRef = in.readUTF();
            var contentType = in.readUTF();
            int len = in.readInt();
            if (len < 0 || len > in.available()) {
                throw new IllegalArgumentException("envelope body length " + len);
            }
            var body = in.readNBytes(len);
            return new TaskEnvelope(taskId, correlationId, replyTo, deadline, traceparent, tracestate, budgetRef,
                    contentType, body);
        } catch (IOException e) {
            throw new IllegalArgumentException("truncated envelope", e);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TaskEnvelope t && Arrays.equals(this.taskId, t.taskId)
                && Arrays.equals(this.correlationId, t.correlationId) && this.replyTo.equals(t.replyTo)
                && this.deadlineMs == t.deadlineMs && this.traceparent.equals(t.traceparent)
                && this.tracestate.equals(t.tracestate) && this.budgetRef.equals(t.budgetRef)
                && this.contentType.equals(t.contentType) && Arrays.equals(this.body, t.body);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(this.taskId) * 31 + Arrays.hashCode(this.correlationId);
    }

    @Override
    public String toString() {
        return "TaskEnvelope[replyTo=" + this.replyTo + ", body=" + this.body.length + "B]";
    }

    // Exact-size fast paths for the common case: every text field is 1..0x7F ASCII, where modified UTF-8
    // (DataOutput.writeUTF) is the bytes themselves. Same wire format; anything else takes the stream path.

    private static boolean ascii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0 || c > 0x7F) return false;
        }
        return s.length() <= 0xFFFF;
    }

    private byte[] encodeAscii() {
        if (!ascii(this.replyTo) || !ascii(this.traceparent) || !ascii(this.tracestate) || !ascii(this.budgetRef)
                || !ascii(this.contentType)) return null;
        int n = 1 + 16 + 16 + 8 + 10 + this.replyTo.length() + this.traceparent.length() + this.tracestate.length()
                + this.budgetRef.length() + this.contentType.length() + 4 + this.body.length;
        var out = new byte[n];
        var b = java.nio.ByteBuffer.wrap(out);
        b.put((byte) VERSION).put(this.taskId).put(this.correlationId).putLong(this.deadlineMs);
        putAscii(b, this.replyTo);
        putAscii(b, this.traceparent);
        putAscii(b, this.tracestate);
        putAscii(b, this.budgetRef);
        putAscii(b, this.contentType);
        b.putInt(this.body.length).put(this.body);
        return out;
    }

    private static void putAscii(java.nio.ByteBuffer b, String s) {
        b.putShort((short) s.length());
        for (int i = 0; i < s.length(); i++) b.put((byte) s.charAt(i));
    }

    /** Null when the payload is not a well-formed all-ASCII v1 envelope (the stream path then decides). */
    private static TaskEnvelope decodeAscii(byte[] p) {
        if (p.length < 1 + 32 + 8 + 10 + 4 || (p[0] & 0xFF) != VERSION) return null;
        int pos = 41;
        var strs = new String[5];
        for (int k = 0; k < 5; k++) {
            if (pos + 2 > p.length) return null;
            int len = ((p[pos] & 0xFF) << 8) | (p[pos + 1] & 0xFF);
            pos += 2;
            if (pos + len > p.length) return null;
            for (int i = pos; i < pos + len; i++) if (p[i] <= 0) return null; // 0 or >= 0x80: not plain ASCII
            strs[k] = len == 0 ? "" : new String(p, pos, len, java.nio.charset.StandardCharsets.ISO_8859_1);
            pos += len;
        }
        if (pos + 4 > p.length) return null;
        var b = java.nio.ByteBuffer.wrap(p);
        int len = b.getInt(pos);
        pos += 4;
        if (len < 0 || len > p.length - pos) return null;
        return new TaskEnvelope(Arrays.copyOfRange(p, 1, 17), Arrays.copyOfRange(p, 17, 33), strs[0], b.getLong(33),
                strs[1], strs[2], strs[3], strs[4], Arrays.copyOfRange(p, pos, pos + len));
    }
}
