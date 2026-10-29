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
package sjws_nettask.messaging.ledger;

/**
 * A replicated produce with a {@code Producer-Id} that the log refused or answered from its dedup state (M2), decided
 * when the entry was applied, identically on every replica.
 */
public final class ProducerSequenceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** What the producer table said about the batch's seq. */
    public enum Kind {
        /** Not the producer's next seq (a gap, a reordered send, or an unknown producer not starting at 1). Nothing appended. */
        OUT_OF_ORDER,
        /**
         * The seq was already appended with another batch (a different record count or records digest): a seq names one
         * batch. Nothing appended.
         */
        SEQ_REUSED,
        /** Already appended, but too long ago for the dedup window to remember its offset. Not appended again. */
        DUPLICATE_OUT_OF_WINDOW,
        /**
         * The log does not know this producer (never seen, or evicted from the producer table) and the seq is not 1.
         * This attempt appended nothing, but an earlier attempt of the same batch may be in the log: the table cannot
         * tell. Reconcile by reading back before resending under a new producer id.
         */
        UNKNOWN_PRODUCER,
        /**
         * Producer dedup is not confirmed on every voter of the topic's group (one runs an older build, or has not
         * answered this leader yet), so the batch was not proposed. Nothing appended; retry later.
         */
        DEDUP_UNAVAILABLE
    }

    private final Kind kind;
    private final long expectedSeq;

    public ProducerSequenceException(Kind kind, long expectedSeq, String message) {
        super(message, null, false, false);
        this.kind = java.util.Objects.requireNonNull(kind);
        this.expectedSeq = expectedSeq;
    }

    public Kind kind() { return kind; }

    /** The seq this producer must send next. */
    public long expectedSeq() { return expectedSeq; }
}
