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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The per-node cancel and deadline set (S2c PR 5) on its own: slot accounting, remote records, the cap. */
class CancelSetTest {

    final AtomicLong now = new AtomicLong(1_000);
    final CancelSet set = new CancelSet(this.now::get);

    @Test
    void aRecordAppliedBeforeTheLocalTrackKeepsOneSlot() {
        assertTrue(this.set.apply("t", 5, 60_000), "a remote cancel for an unknown task creates it cancelled");
        assertEquals(1, this.set.size());
        assertTrue(this.set.reserve());
        assertEquals(2, this.set.size());
        this.set.trackReserved("t", 5, 60_000); // the local submit lands after the record: the reserved slot is given back
        assertEquals(1, this.set.size());
        assertEquals(CancelSet.CANCELLED, this.set.reason("t", 5, this.now.get()), "the earlier cancel wins");
    }

    @Test
    void aRemoteRecordCancelsATrackedTaskOnce() {
        assertTrue(this.set.track("t", 7, 60_000));
        assertNull(this.set.reason("t", 7, this.now.get()), "running within its deadline");
        assertTrue(this.set.apply("t", 7, 60_000));
        assertEquals(CancelSet.CANCELLED, this.set.reason("t", 7, this.now.get()));
        assertFalse(this.set.apply("t", 7, 60_000), "a replayed record changes nothing");
        assertFalse(this.set.cancel("t", 7), "nor does a local cancel after it");
        assertEquals(1, this.set.size());
        assertTrue(this.set.probe("t").isCancelled("g", 7));
    }

    @Test
    void remoteRecordsPastTheCapAreKeptAndCounted() {
        for (int i = 0; i < CancelSet.MAX_ENTRIES; i++) {
            assertTrue(this.set.track("t", i, 60_000));
        }
        assertFalse(this.set.track("t", -1, 60_000), "a local stream is refused at the cap");
        assertEquals(1, this.set.refused());
        assertTrue(this.set.apply("t", CancelSet.MAX_ENTRIES, 60_000), "a remote cancel is never dropped");
        assertEquals(1, this.set.overCap());
        assertEquals(CancelSet.MAX_ENTRIES + 1, this.set.size());
        assertEquals(CancelSet.CANCELLED, this.set.reason("t", CancelSet.MAX_ENTRIES, this.now.get()));
        this.now.set(60_000 + CancelSet.GRACE_MS + 1); // all past deadline + grace: the sweep inside reserve frees them
        assertTrue(this.set.reserve());
        assertEquals(1, this.set.size());
    }

    @Test
    void onlyATaggedRecordOfTheExactSizeIsACancelRecord() {
        var r = CancelSet.record(9, 60_000, new byte[16]);
        assertTrue(CancelSet.isRecord(r));
        assertEquals(9, CancelSet.recordTarget(r));
        var other = r.clone();
        other[0] = 1; // a TaskEnvelope version byte
        assertFalse(CancelSet.isRecord(other));
        assertFalse(CancelSet.isRecord(new byte[CancelSet.RECORD_BYTES + 1]));
    }
}
