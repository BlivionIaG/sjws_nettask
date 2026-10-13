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

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * The task and reply envelope carried in the message payload until messaging has headers
 * (docs/design/agent-swarm.md §4.5). Layout, version 1, big-endian:
 * {@code [version:u8][taskId:16][correlationId:16][deadlineMs:i64][replyTo][traceparent][tracestate][budgetRef]
 * [contentType][bodyLen:i32][body]}, where each text field is {@code [len:u16][UTF-8]}.
 * <p>
 * Immutable. The public constructor and accessors copy arrays; the codec and the swarm's own hot path
 * (encode, decode, reply) do not, so a request/reply round trip copies a body only where messaging does.
 */
public final class TaskEnvelope {

    public static final int VERSION = 1;
    /** Inline body limit (§5.2, Messaging Core): larger results go to the swarm-owned blob store by reference. */
    public static final int MAX_INLINE_BODY = 1 << 20;
    /** No deadline. */
    public static final long NO_DEADLINE = Long.MAX_VALUE;
    private static final int MAX_TEXT = 0xffff;

    private final byte[] taskId;
    private final byte[] correlationId;
    private final String replyTo;
    private final long deadlineMs;
    private final String traceparent;
    private final String tracestate;
    private final String budgetRef;
    private final String contentType;
    private final byte[] body;

    public TaskEnvelope(byte[] taskId, byte[] correlationId, String replyTo, long deadlineMs, String traceparent,
            String tracestate, String budgetRef, String contentType, byte[] body) {
        this(taskId.clone(), correlationId.clone(), replyTo, deadlineMs, traceparent, tracestate, budgetRef,
                contentType, body.clone(), false);
    }

    /** Takes ownership of the arrays. */
    private TaskEnvelope(byte[] taskId, byte[] correlationId, String replyTo, long deadlineMs, String traceparent,
            String tracestate, String budgetRef, String contentType, byte[] body, boolean owned) {
        if (taskId.length != 16 || correlationId.length != 16) {
            throw new IllegalArgumentException("taskId and correlationId are 16 bytes");
        }
        if (body.length > MAX_INLINE_BODY) {
            throw new IllegalArgumentException("body larger than " + MAX_INLINE_BODY + " bytes; store it by reference");
        }
        this.taskId = taskId;
        this.correlationId = correlationId;
        this.replyTo = Objects.requireNonNullElse(replyTo, "");
        this.deadlineMs = deadlineMs;
        this.traceparent = Objects.requireNonNullElse(traceparent, "");
        this.tracestate = Objects.requireNonNullElse(tracestate, "");
        this.budgetRef = Objects.requireNonNullElse(budgetRef, "");
        this.contentType = Objects.requireNonNullElse(contentType, "");
        this.body = body;
    }

    /** As the public constructor, taking ownership of {@code ids} and {@code body} (the caller must not reuse them). */
    static TaskEnvelope owned(byte[] taskId, byte[] correlationId, String replyTo, long deadlineMs, String traceparent,
            String contentType, byte[] body) {
        return new TaskEnvelope(taskId, correlationId, replyTo, deadlineMs, traceparent, "", "", contentType, body, true);
    }

    public byte[] taskId() {
        return this.taskId.clone();
    }

    public byte[] correlationId() {
        return this.correlationId.clone();
    }

    public String replyTo() {
        return this.replyTo;
    }

    public long deadlineMs() {
        return this.deadlineMs;
    }

    public String traceparent() {
        return this.traceparent;
    }

    public String tracestate() {
        return this.tracestate;
    }

    public String budgetRef() {
        return this.budgetRef;
    }

    public String contentType() {
        return this.contentType;
    }

    public byte[] body() {
        return this.body.clone();
    }

    /** The body without a copy; callers must not modify it. */
    byte[] bodyView() {
        return this.body;
    }

    /** High and low halves of the correlation id, for the reply table key. */
    long correlationHi() {
        return ByteBuffer.wrap(this.correlationId).getLong(0);
    }

    long correlationLo() {
        return ByteBuffer.wrap(this.correlationId).getLong(8);
    }

    /** True when the caller's deadline is set and has passed at {@code nowMs}. */
    public boolean expiredAt(long nowMs) {
        return this.deadlineMs != NO_DEADLINE && nowMs >= this.deadlineMs;
    }

    /** The reply to this task: same ids, empty {@code replyTo}, the given body (copied). */
    public TaskEnvelope reply(String contentType, byte[] body) {
        return replyOwned(contentType, body.clone());
    }

    /** As {@link #reply}, taking ownership of {@code body}. */
    TaskEnvelope replyOwned(String contentType, byte[] body) {
        return new TaskEnvelope(this.taskId, this.correlationId, "", this.deadlineMs, this.traceparent, this.tracestate,
                this.budgetRef, contentType, body, true);
    }

    private static byte[] utf8(String s) {
        var b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length > MAX_TEXT) {
            throw new IllegalArgumentException("text field longer than " + MAX_TEXT + " bytes");
        }
        return b;
    }

    public byte[] encode() {
        var replyTo = utf8(this.replyTo);
        var tp = this.traceparent.isEmpty() ? EMPTY : utf8(this.traceparent);
        var ts = this.tracestate.isEmpty() ? EMPTY : utf8(this.tracestate);
        var br = this.budgetRef.isEmpty() ? EMPTY : utf8(this.budgetRef);
        var ct = this.contentType.isEmpty() ? EMPTY : utf8(this.contentType);
        int n = 1 + 16 + 16 + 8 + 10 + replyTo.length + tp.length + ts.length + br.length + ct.length + 4 + this.body.length;
        var out = ByteBuffer.allocate(n);
        out.put((byte) VERSION).put(this.taskId).put(this.correlationId).putLong(this.deadlineMs);
        for (var s : new byte[][] {replyTo, tp, ts, br, ct}) {
            out.putShort((short) s.length).put(s);
        }
        out.putInt(this.body.length).put(this.body);
        return out.array();
    }

    private static final byte[] EMPTY = new byte[0];

    private static String text(ByteBuffer in) {
        int len = Short.toUnsignedInt(in.getShort());
        if (len == 0) {
            return "";
        }
        var s = new String(in.array(), in.arrayOffset() + in.position(), len, StandardCharsets.UTF_8);
        in.position(in.position() + len);
        return s;
    }

    /** Decodes {@link #encode()}; a wrong version or a truncated payload is an {@link IllegalArgumentException}. */
    public static TaskEnvelope decode(byte[] payload) {
        try {
            var in = ByteBuffer.wrap(payload);
            int version = Byte.toUnsignedInt(in.get());
            if (version != VERSION) {
                throw new IllegalArgumentException("envelope version " + version);
            }
            var taskId = new byte[16];
            in.get(taskId);
            var correlationId = new byte[16];
            in.get(correlationId);
            long deadline = in.getLong();
            if (in.remaining() < 10) {
                throw new BufferUnderflowException();
            }
            var replyTo = text(in);
            var traceparent = text(in);
            var tracestate = text(in);
            var budgetRef = text(in);
            var contentType = text(in);
            int len = in.getInt();
            if (len < 0 || len > in.remaining()) {
                throw new IllegalArgumentException("envelope body length " + len);
            }
            var body = new byte[len];
            in.get(body);
            return new TaskEnvelope(taskId, correlationId, replyTo, deadline, traceparent, tracestate, budgetRef,
                    contentType, body, true);
        } catch (BufferUnderflowException | IndexOutOfBoundsException e) {
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
}
