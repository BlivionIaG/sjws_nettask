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

import sjws_nettask.core.Request;

/**
 * Admission for {@code /swarm/v1} (docs/design/agent-swarm.md §6, §12 S2 note). Deny by default: a request is
 * served only when {@link #authenticate} names an agent and {@link #allows} grants the target pool.
 * <p>
 * Interim implementation: {@link TokenGate} (bearer tokens). S2d replaces it with the {@code AgentPolicy} document and
 * agent mTLS certificates by implementing this interface; {@link SwarmHttp} does not change.
 */
public interface SwarmGate {

    /** An authenticated agent. Never carries the credential. */
    record Agent(String id) { }

    /** Denies everything: the gate when nothing is configured (fail closed). */
    SwarmGate DENY_ALL = new SwarmGate() {
        @Override
        public Agent authenticate(Request request) {
            return null;
        }

        @Override
        public boolean allows(Agent agent, String pool) {
            return false;
        }

        @Override
        public boolean isAdmin(Agent agent) {
            return false;
        }

        @Override
        public long maxRequestBytes() {
            return 0;
        }

        @Override
        public String toString() {
            return "SwarmGate[deny-all]";
        }
    };

    /** @return the agent, or null when the request carries no valid credential */
    Agent authenticate(Request request);

    /** @return whether {@code agent} may submit to, claim from or settle leases of {@code pool} */
    boolean allows(Agent agent, String pool);

    /**
     * Whether {@code agent} is an admin: may cancel any agent's stream (S2c PR 5). Default: nobody. {@link TokenGate}:
     * an agent granted pool {@code *}. S2d's {@code AgentPolicy} replaces this with an explicit role. Abstract on
     * purpose: a default method would make loading any gate initialize this interface (and load {@link #DENY_ALL}'s
     * class) with streaming off.
     */
    boolean isAdmin(Agent agent);

    /** Largest request body accepted, in bytes; larger (or unknown-length beyond it) bodies get 413. */
    long maxRequestBytes();
}
