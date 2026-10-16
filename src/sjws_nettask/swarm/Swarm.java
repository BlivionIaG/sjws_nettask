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
import java.util.function.LongSupplier;
import sjws_nettask.messaging.delivery.RetryPolicy;

/**
 * The S1 services on one node (agent-swarm.md §12 S1): task submit/claim/settle with fixed or kept leases,
 * the DLQ, and request/reply on {@code reply.<id>}. Closing it stops the poller and closes every partition.
 */
public final class Swarm implements AutoCloseable {

    private final Topics topics;
    private final TaskService tasks;
    private final ReplyRouter replies;

    private Swarm(Topics topics, String id, long producerId, long idleCloseMs) {
        this.topics = topics;
        this.tasks = new TaskService(topics);
        this.replies = new ReplyRouter(topics, this.tasks, id, producerId, idleCloseMs);
        this.tasks.bindLocalReplies(this.replies);
    }

    public static Swarm open(Path dir, String id, long producerId, RetryPolicy policy, LongSupplier clockMs,
            long idleCloseMs) {
        return new Swarm(new Topics(dir, policy, clockMs), id, producerId, idleCloseMs);
    }

    public static Swarm open(Path dir, String id, long producerId) {
        return open(dir, id, producerId, RetryPolicy.DEFAULT, System::currentTimeMillis,
                ReplyRouter.DEFAULT_IDLE_CLOSE_MS);
    }

    public TaskService tasks() {
        return this.tasks;
    }

    public ReplyRouter replies() {
        return this.replies;
    }

    public Topics topics() {
        return this.topics;
    }

    /** A lease keeper for one worker, using the batched fenced extend. */
    /**
     * Turns on the S2c stream produce path with {@code config} (call once, at startup, only when
     * {@code sjws.swarm.stream.enabled} is on). The node id names the stream shards {@code stream.<node>#i}.
     */
    public TokenStreams enableStreams(TokenStreams.Config config) {
        var s = new TokenStreams(this.topics, this.replies.topic().substring("reply.".length()), config);
        this.topics.installProbes(s.cancels()::probe); // S2c PR 5: extend answers CANCELLED for cancelled tasks
        this.tasks.bindStreams(s);
        return s;
    }

    public LeaseKeeper leaseKeeper(long visibilityMs, java.util.function.BiConsumer<Task, sjws_nettask.messaging.delivery.LeaseResult> lost) {
        return new LeaseKeeper(this.tasks, visibilityMs, lost);
    }

    @Override
    public void close() {
        this.replies.close();
        this.topics.close();
    }
}
