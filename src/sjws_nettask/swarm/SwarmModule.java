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
import java.security.SecureRandom;
import sjws_nettask.OptionalModule;
import sjws_nettask.core.HandlerContributor;
import sjws_nettask.core.HandlerMounts;

/**
 * Entry point of the optional agent-swarm layer (docs/design/agent-swarm.md). Loaded only by name from
 * {@code sjws_nettask.OptionalModules} when the swarm is enabled; nothing outside this package imports it.
 * <p>
 * S1b: mounts the unary {@code /swarm/v1/*} endpoints ({@link SwarmHttp}) at startup. The services start
 * no thread and touch no file until the first request: partitions open on first use under
 * {@code -Dsjws.swarm.dir} / {@code SJWS_SWARM_DIR} (default {@code swarm-data} in the working directory),
 * and the reply topic is {@code reply.<sjws.swarm.node | SJWS_SWARM_NODE | swarm-<pid>>}.
 */
public final class SwarmModule implements OptionalModule, HandlerContributor {

    static final String DIR_PROPERTY = "sjws.swarm.dir";
    static final String DIR_ENV = "SJWS_SWARM_DIR";
    static final String NODE_PROPERTY = "sjws.swarm.node";
    static final String NODE_ENV = "SJWS_SWARM_NODE";
    static final String TRACE_PROPERTY = "sjws.swarm.trace.enabled";
    static final String TRACE_ENV = "SJWS_SWARM_TRACE_ENABLED";

    private final Swarm swarm;
    private final SwarmGate gate;
    private final boolean trace;
    private volatile boolean closed;

    public SwarmModule() {
        this(Swarm.open(Path.of(setting(DIR_PROPERTY, DIR_ENV, "swarm-data")),
                setting(NODE_PROPERTY, NODE_ENV, "swarm-" + ProcessHandle.current().pid()), new SecureRandom().nextLong()),
                TokenGate.configured(System::getProperty, System.getenv(), System.err));
    }

    SwarmModule(Swarm swarm, SwarmGate gate) {
        this(swarm, gate, Boolean.parseBoolean(setting(TRACE_PROPERTY, TRACE_ENV, "false")));
    }

    SwarmModule(Swarm swarm, SwarmGate gate, boolean trace) {
        this.swarm = swarm;
        this.gate = gate;
        this.trace = trace;
        if (trace) {
            // S2b, off by default: the only place the trace package is named outside SwarmHttp's guarded paths
            System.err.println("swarm: " + sjws_nettask.swarm.trace.SwarmTrace.describe());
        }
    }

    boolean trace() {
        return this.trace;
    }

    SwarmGate gate() {
        return this.gate;
    }

    static String setting(String property, String env, String dflt) {
        var v = System.getProperty(property);
        if (v == null || v.isBlank()) {
            v = System.getenv(env);
        }
        return v == null || v.isBlank() ? dflt : v.trim();
    }

    public Swarm swarm() {
        return this.swarm;
    }

    @Override
    public void contribute(HandlerMounts mounts) {
        mounts.mount("/swarm/v1", new SwarmHttp(this.swarm, new SecureRandom().nextLong(), this.gate, this.trace));
    }

    public boolean closed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            this.swarm.close();
        }
    }
}
