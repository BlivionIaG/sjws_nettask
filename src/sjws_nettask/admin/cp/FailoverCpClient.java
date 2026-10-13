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
package sjws_nettask.admin.cp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Talks to any of several CP endpoints. Sticky: keeps using the endpoint that last answered. On
 * {@code UNAVAILABLE} it moves to the next endpoint; on {@code NOT_LEADER} with a known hint it
 * jumps to the leader. Each call tries at most {@code 2 × endpoints} times. Writes are retried
 * too: the caller's idempotency key makes the retry safe.
 */
public final class FailoverCpClient implements CpClient {

    @FunctionalInterface
    interface Call<T> {
        T run(CpClient client) throws CpException;
    }

    private final List<String> endpoints;
    private final Function<String, CpClient> connector;
    private final Map<String, CpClient> clients = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private int current;
    private final List<String> events = new ArrayList<>();

    /** @param connector opens a client for one endpoint (called lazily, once per endpoint) */
    public FailoverCpClient(List<String> endpoints, Function<String, CpClient> connector) {
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("at least one endpoint");
        }
        this.endpoints = List.copyOf(endpoints);
        this.connector = connector;
    }

    @Override
    public String endpoint() {
        this.lock.lock();
        try {
            return this.endpoints.get(this.current);
        } finally {
            this.lock.unlock();
        }
    }

    /** Endpoint switches, oldest first (shown by the console). */
    public List<String> events() {
        this.lock.lock();
        try {
            return List.copyOf(this.events);
        } finally {
            this.lock.unlock();
        }
    }

    <T> T call(Call<T> call) throws CpException {
        this.lock.lock(); // one call at a time: the console is single-user
        try {
            CpException last = null;
            for (int attempt = 0; attempt < 2 * this.endpoints.size(); attempt++) {
                var ep = this.endpoints.get(this.current);
                try {
                    var client = this.clients.get(ep);
                    if (client == null) {
                        client = this.connector.apply(ep);
                        this.clients.put(ep, client);
                    }
                    return call.run(client);
                } catch (CpException e) {
                    last = e;
                    if (e.kind() == CpException.Kind.UNAVAILABLE) {
                        switchTo((this.current + 1) % this.endpoints.size(), ep + " unavailable");
                    } else if (e.kind() == CpException.Kind.NOT_LEADER && e.leaderHint() != null
                            && this.endpoints.contains(e.leaderHint()) && !e.leaderHint().equals(ep)) {
                        switchTo(this.endpoints.indexOf(e.leaderHint()), ep + " is not the leader");
                    } else {
                        throw e;
                    }
                }
            }
            throw new CpException(CpException.Kind.UNAVAILABLE,
                    "no endpoint answered (" + String.join(", ", this.endpoints) + "); last: " + last.getMessage());
        } finally {
            this.lock.unlock();
        }
    }

    private void switchTo(int index, String why) {
        this.current = index;
        this.events.add(why + " -> " + this.endpoints.get(index));
    }

    @Override
    public ClusterView cluster() throws CpException {
        return call(CpClient::cluster);
    }

    @Override
    public long head() throws CpException {
        return call(CpClient::head);
    }

    @Override
    public ConfigBundle config(long version) throws CpException {
        return call(c -> c.config(version));
    }

    @Override
    public Validation validate(ConfigBundle draft) throws CpException {
        return call(c -> c.validate(draft));
    }

    @Override
    public long stage(ConfigBundle draft, long baseVersion, String key) throws CpException {
        return call(c -> c.stage(draft, baseVersion, key));
    }

    @Override
    public Rollout rollout(long version, String selector, String key) throws CpException {
        return call(c -> c.rollout(version, selector, key));
    }

    @Override
    public Rollout rolloutStatus(String id) throws CpException {
        return call(c -> c.rolloutStatus(id));
    }

    @Override
    public long rollback(long toVersion, String key) throws CpException {
        return call(c -> c.rollback(toVersion, key));
    }

    @Override
    public List<AuditEntry> audit(long afterSeq, int limit) throws CpException {
        return call(c -> c.audit(afterSeq, limit));
    }
}
