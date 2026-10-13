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
    void joinTokenIsOneShot() {
        var secret = new byte[16];
        Arrays.fill(secret, (byte) 7);
        var hash = JoinTokens.sha256(secret);
        var tokens = new JoinTokens();
        assertEquals("token", tokens.consume("edge-1", "tok", hash, 1_000).error(), "not issued");
        assertTrue(tokens.issue("tok", hash, 1_000 + JoinTokens.DEFAULT_TTL_MILLIS, 1_000));
        assertFalse(tokens.issue("tok", "other", 9_999_999, 1_000), "an id is issued once");
        assertTrue(tokens.evaluate("edge-1", "tok", hash, 1_000).accepted());
        assertNull(tokens.evaluate("edge-1", "tok", hash, 1_000).join(), "evaluate does not consume");

        var created = tokens.consume("edge-1", "tok", hash, 2_000);
        assertTrue(created.accepted());
        assertFalse(created.replay());
        assertEquals("edge-1", tokens.joined("edge-1").nodeId());

        // burned: another node can never use it
        assertEquals("used", tokens.consume("edge-2", "tok", hash, 2_001).error());
        assertNull(tokens.joined("edge-2"));
        // a lost-response retry from the same node gets its recorded join back, without a second consume
        var again = tokens.consume("edge-1", "tok", hash, 3_000);
        assertTrue(again.replay());
        assertEquals(created.join(), again.join());
        // ... only within the grace window, even though the token's own TTL has not run out
        assertEquals("used", tokens.evaluate("edge-1", "tok", hash, 2_000 + JoinTokens.REPLAY_GRACE_MILLIS + 1).error());
        // and only with the same secret
        var other = secret.clone();
        other[0] = 1;
        assertEquals("token", tokens.evaluate("edge-1", "tok", JoinTokens.sha256(other), 3_000).error());

        // expiry, identity and input checks
        tokens.issue("tok2", hash, 5_000, 1_000);
        assertEquals("expired", tokens.consume("edge-3", "tok2", hash, 5_001).error());
        assertEquals("identity", tokens.consume("edge-1", "tok2", hash, 4_000).error(), "already joined");
        assertEquals("identity", tokens.evaluate("bad id", "tok2", hash, 1_000).error());
        assertEquals("identity", tokens.evaluate(null, "tok2", hash, 1_000).error());
        assertEquals("token", tokens.evaluate("edge-3", null, hash, 1_000).error());
        assertEquals("token", tokens.evaluate("edge-3", "tok2", null, 1_000).error());
        assertNull(JoinTokens.checkIssue("tok3", secret, 1_000));
        assertEquals("token", JoinTokens.checkIssue("bad id", secret, 1_000));
        assertEquals("token", JoinTokens.checkIssue(null, secret, 1_000));
        assertEquals("secret", JoinTokens.checkIssue("tok3", new byte[8], 1_000));
        assertEquals("secret", JoinTokens.checkIssue("tok3", null, 1_000));
        assertEquals("ttl", JoinTokens.checkIssue("tok3", secret, 0));
        assertEquals("ttl", JoinTokens.checkIssue("tok3", secret, JoinTokens.MAX_TTL_MILLIS + 1));
    }

    @Test
    void issuedTokensAreBounded() {
        var tokens = new JoinTokens();
        var h = JoinTokens.sha256(new byte[16]);
        for (int i = 0; i < JoinTokens.MAX_TOKENS; i++) {
            assertTrue(tokens.issue("t" + i, h, 10, 0));
        }
        assertFalse(tokens.issue("live", h, 100, 5), "full of unexpired tokens");
        assertTrue(tokens.issue("after", h, 100, 11), "expired unused tokens are pruned");
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
