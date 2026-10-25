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

import java.nio.file.Path;
import sjws_nettask.core.HandlerContributor;

/**
 * Test support for transport tests outside this package (e.g. {@code h3}): a swarm with token streaming on, mounted at
 * {@code /swarm/v1} behind an allow-all gate ({@link StreamHttpTest#BY_HEADER}).
 */
public final class SwarmMountForTests implements AutoCloseable {

    private final Swarm swarm;
    private final SwarmHttp http;

    private SwarmMountForTests(Swarm swarm, SwarmHttp http) {
        this.swarm = swarm;
        this.http = http;
    }

    /** Streaming on with {@code window} / {@code sinkChunks} and the credit sub-flag. */
    public static SwarmMountForTests streaming(Path dir, int window, int sinkChunks, boolean credit) {
        var swarm = Swarm.open(dir, "mount-test", 3);
        swarm.enableStreams(new TokenStreams.Config(1, window, sinkChunks, credit));
        return new SwarmMountForTests(swarm, new SwarmHttp(swarm, 4, StreamHttpTest.BY_HEADER));
    }

    public HandlerContributor contributor() {
        return mounts -> mounts.mount("/swarm/v1", this.http);
    }

    @Override
    public void close() {
        this.swarm.close();
    }
}
