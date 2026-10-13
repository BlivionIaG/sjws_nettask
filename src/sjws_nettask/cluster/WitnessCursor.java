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

/**
 * Last {@code (index, term)} a witness has acked. {@link #note} only stages a pair that is
 * strictly ahead in Raft order. {@link #publish} runs after the storage has made that pair durable.
 */
final class WitnessCursor {

    private long index;
    private long term;
    private long stagedIndex;
    private long stagedTerm;
    private boolean dirty;

    long index() {
        return index;
    }

    long term() {
        return term;
    }

    boolean dirty() {
        return dirty;
    }

    long stagedIndex() {
        return stagedIndex;
    }

    long stagedTerm() {
        return stagedTerm;
    }

    void load(long index, long term) {
        this.index = index;
        this.term = term;
        this.stagedIndex = index;
        this.stagedTerm = term;
        this.dirty = false;
    }

    /** Ignore a pair that is empty or not strictly ahead of the staged cursor. */
    void note(long index, long term) {
        if (index < 1 || term < 0) {
            return;
        }
        long baseIndex = dirty ? stagedIndex : this.index;
        long baseTerm = dirty ? stagedTerm : this.term;
        if (term < baseTerm || (term == baseTerm && index <= baseIndex)) {
            return;
        }
        stagedIndex = index;
        stagedTerm = term;
        dirty = true;
    }

    void publish() {
        if (!dirty) {
            return;
        }
        index = stagedIndex;
        term = stagedTerm;
        dirty = false;
    }
}
