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
package sjws_nettask.cp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Lifecycle, join-token, and status rules from the control-plane design. */
class LifecycleTest {

    @Test
    void rolePrecedenceAndReadiness() {
        assertEquals(NodeLifecycle.Startup.CONTROL, NodeLifecycle.role("control", "data", "standalone"));
        assertEquals(NodeLifecycle.Startup.DATA, NodeLifecycle.role(null, "data", "standalone"));
        assertEquals(NodeLifecycle.Startup.DATA, NodeLifecycle.role("  ", "data", "control"));
        assertEquals(NodeLifecycle.Startup.STANDALONE, NodeLifecycle.role(null, null, "standalone"));
        assertEquals(NodeLifecycle.Startup.STANDALONE, NodeLifecycle.role("", " ", null));
        assertThrows(IllegalArgumentException.class, () -> NodeLifecycle.role("gateway", null, null));
        assertFalse(NodeLifecycle.bindsContentPort(NodeLifecycle.Startup.CONTROL, false));
        assertTrue(NodeLifecycle.bindsContentPort(NodeLifecycle.Startup.CONTROL, true));
        assertTrue(NodeLifecycle.bindsContentPort(NodeLifecycle.Startup.DATA, false));
        assertFalse(NodeLifecycle.sighupPullsFromControlPlane());
        assertFalse(NodeLifecycle.controlReady(true, false));
        assertFalse(NodeLifecycle.controlReady(false, true));
        assertTrue(NodeLifecycle.controlReady(true, true));
        var down = NodeLifecycle.dataPlane(false, false, false);
        assertFalse(down.ready());
        assertFalse(down.serving());
        assertTrue(down.retrying());
        assertFalse(down.drain());
        var stale = NodeLifecycle.dataPlane(true, true, true);
        assertFalse(stale.ready());
        assertTrue(stale.serving());
        assertFalse(stale.retrying());
        var quiet = NodeLifecycle.dataPlane(false, true, false);
        assertTrue(quiet.ready());
        assertTrue(quiet.retrying());
        var caughtUp = NodeLifecycle.dataPlane(true, true, false);
        assertTrue(caughtUp.ready());
        assertFalse(caughtUp.retrying());
        var staleDown = NodeLifecycle.dataPlane(false, true, true);
        assertFalse(staleDown.ready());
        assertTrue(staleDown.retrying());
        assertFalse(staleDown.drain());
        assertEquals(NodeLifecycle.DRAIN_BUDGET_MS, NodeLifecycle.drainBudgetMillis(false, 60_000, 60_000));
        assertEquals(NodeLifecycle.DRAIN_BUDGET_MS, NodeLifecycle.drainBudgetMillis(true, 5_000, 15_000));
        assertThrows(IllegalArgumentException.class, () -> NodeLifecycle.drainBudgetMillis(true, 20_000, 20_000));
        assertEquals(NodeLifecycle.DRAIN_BUDGET_MS, NodeLifecycle.drainBudgetMillis(true, -1, -1));
    }

    @Test
    void joinTokenIsIdempotentPerIdentity() {
        var secret = new byte[16];
        Arrays.fill(secret, (byte) 7);
        var tokens = new JoinTokens();
        var created = tokens.present("edge-1", "tok", secret, 1_000, JoinTokens.DEFAULT_TTL_MILLIS);
        assertTrue(created.accepted());
        assertFalse(created.replay());
        var again = tokens.present("edge-1", "tok", secret, 1_000, JoinTokens.DEFAULT_TTL_MILLIS);
        assertTrue(again.replay());
        assertEquals(created.join(), again.join());
        assertEquals("identity", tokens.present("other", "tok", secret, 1_000, 1_000).error());
        assertEquals("identity", tokens.present(null, "tok", secret, 1_000, 1_000).error());
        assertEquals("token", tokens.present("edge-2", null, secret, 1_000, 1_000).error());
        assertEquals("token", tokens.present("edge-2", "tok2", null, 1_000, 1_000).error());
        assertFalse(tokens.present("edge-2", null, secret, 1_000, 1_000).accepted());
        assertEquals("identity", tokens.present("bad id", "tok", secret, 1_000, 1_000).error());
        assertEquals("token", tokens.present("edge-2", " ", secret, 1_000, 1_000).error());
        assertEquals("token", tokens.present("edge-2", "tok2", new byte[8], 1_000, 1_000).error());
        assertEquals("ttl", tokens.present("edge-2", "tok2", secret, 1_000, 0).error());
        assertEquals("ttl", tokens.present("edge-2", "tok2", secret, 1_000, JoinTokens.MAX_TTL_MILLIS + 1).error());
        assertEquals("expired", tokens.present("edge-1", "tok", secret, created.join().expiresAtMillis() + 1,
                1_000).error());
        var other = secret.clone();
        other[0] = 1;
        var fresh = new JoinTokens();
        fresh.present("edge-1", "tok", secret, 1_000, 1_000);
        assertEquals("identity", fresh.present("edge-1", "other-token", secret, 1_000, 1_000).error());
        assertEquals("identity", fresh.present("edge-1", "tok", other, 1_000, 1_000).error());
        assertEquals("ttl", fresh.present("edge-3", "tok3", secret, Long.MAX_VALUE, 1_000).error());
    }

    @Test
    void statusBindingReportsLeaderOnlyWhenReady() {
        assertEquals("leader", StatusBinding.control(true, true, 4).connectionState());
        assertEquals(4, StatusBinding.control(true, true, 4).configVersion());
        assertEquals("connecting", StatusBinding.control(true, false, 4).connectionState());
        assertEquals("follower", StatusBinding.control(false, false, 4).connectionState());
        assertEquals("connected", StatusBinding.data(true, false, 2).connectionState());
        assertEquals("disconnected", StatusBinding.data(false, true, 2).connectionState());
        assertEquals("pending", StatusBinding.data(false, false, 9).connectionState());
        assertEquals(0, StatusBinding.data(false, false, 9).configVersion());
        assertEquals(0, new StatusBinding("nope", -3).configVersion());
        assertEquals("disconnected", new StatusBinding("nope", 1).connectionState());
    }
}
