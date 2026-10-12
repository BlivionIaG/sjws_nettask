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
package sjws_nettask.clusterharness;

import java.util.Map;
import sjws_nettask.SjwsServer;

/**
 * One data-plane process. Today this is a real {@link SjwsServer} on an ephemeral port.
 * {@link #version()} is the design's {@code AppliedConfig.version}: {@code 0} until a
 * control-plane snapshot has been applied.
 */
public interface DataNode extends AutoCloseable {

    String id();

    /** Rollout labels (design §3.3). Recorded by the harness; the server ignores them until cp exists. */
    Map<String, String> labels();

    /** Content listener. Always the kernel-assigned port ({@code ServerConfig} port 0). */
    int httpPort();

    /** Desired-config version. {@code 0} when the snapshot never came from the control plane. */
    long version();

    /** Lowercase SHA-256 of the applied canonical YAML, or null when the node has no cp snapshot. */
    String contentHash();

    SjwsServer server();

    @Override
    void close();
}
