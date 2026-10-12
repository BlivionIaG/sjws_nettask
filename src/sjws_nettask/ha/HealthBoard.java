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
package sjws_nettask.ha;

import java.nio.charset.StandardCharsets;
import sjws_nettask.core.Body;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;

/**
 * Liveness and readiness as eight precomputed {@link Response}s. A probe returns one of
 * them; it does not allocate and it does not touch the file handler.
 */
public final class HealthBoard {

    private static final byte[] OK = "ok\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UNAVAILABLE = "unavailable\n".getBytes(StandardCharsets.UTF_8);

    private final Response live;
    private final Response liveHead;
    private final Response dead;
    private final Response deadHead;
    private final Response ready;
    private final Response readyHead;
    private final Response notReady;
    private final Response notReadyHead;

    private volatile boolean alive = true;
    private volatile boolean snapshotLoaded;
    private volatile boolean draining;
    private volatile boolean quorumOk = true;
    private volatile boolean requireQuorum;
    private volatile Runnable onChange = () -> {
    };

    public HealthBoard(boolean requireQuorum) {
        this.requireQuorum = requireQuorum;
        this.live = text(200, OK, false);
        this.liveHead = text(200, OK, true);
        this.dead = text(503, UNAVAILABLE, false);
        this.deadHead = text(503, UNAVAILABLE, true);
        this.ready = text(200, OK, false);
        this.readyHead = text(200, OK, true);
        this.notReady = text(503, UNAVAILABLE, false);
        this.notReadyHead = text(503, UNAVAILABLE, true);
    }

    public void onChange(Runnable listener) {
        this.onChange = listener == null ? () -> {
        } : listener;
    }

    public Response health(boolean head) {
        if (this.alive) {
            return head ? this.liveHead : this.live;
        }
        return head ? this.deadHead : this.dead;
    }

    public Response ready(boolean head) {
        if (readyNow()) {
            return head ? this.readyHead : this.ready;
        }
        return head ? this.notReadyHead : this.notReady;
    }

    public boolean alive() {
        return this.alive;
    }

    public boolean readyNow() {
        return this.snapshotLoaded && !this.draining && (!this.requireQuorum || this.quorumOk);
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
        changed();
    }

    public void setSnapshotLoaded(boolean loaded) {
        this.snapshotLoaded = loaded;
        changed();
    }

    public void setDraining(boolean draining) {
        this.draining = draining;
        changed();
    }

    public void setQuorumOk(boolean quorumOk) {
        this.quorumOk = quorumOk;
        changed();
    }

    public void setRequireQuorum(boolean requireQuorum) {
        this.requireQuorum = requireQuorum;
        changed();
    }

    public boolean draining() {
        return this.draining;
    }

    private void changed() {
        this.onChange.run();
    }

    private static Response text(int status, byte[] body, boolean head) {
        var headers = ResponseHeaders.of().add("content-type", "text/plain; charset=utf-8");
        return new Response(status, headers, Body.Bytes.of(body), head);
    }
}
