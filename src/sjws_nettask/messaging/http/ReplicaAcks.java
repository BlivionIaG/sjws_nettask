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
package sjws_nettask.messaging.http;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Primary side of standby replication (durable-log-plan §3): per topic, the offset up to which the standby has made
 * the log durable, as it reports on its fetches. A {@code replicated} produce waits here for its records.
 */
final class ReplicaAcks {

    private static final class Topic {
        final ReentrantLock lock = new ReentrantLock();
        final Condition advanced = lock.newCondition();
        long acked;
    }

    private final ConcurrentHashMap<String, Topic> topics = new ConcurrentHashMap<>();

    private Topic topic(String t) {
        var x = this.topics.get(t);
        if (x == null) {
            x = new Topic();
            var prev = this.topics.putIfAbsent(t, x);
            if (prev != null) x = prev;
        }
        return x;
    }

    /** The standby is durable through {@code offset} (exclusive) on {@code topic}; never moves backwards. */
    void advance(String topic, long offset) {
        var t = topic(topic);
        t.lock.lock();
        try {
            if (offset > t.acked) {
                t.acked = offset;
                t.advanced.signalAll();
            }
        } finally {
            t.lock.unlock();
        }
    }

    long acked(String topic) {
        var t = this.topics.get(topic);
        if (t == null) return 0;
        t.lock.lock();
        try {
            return t.acked;
        } finally {
            t.lock.unlock();
        }
    }

    /** Waits until the standby is durable through {@code offset}; false on timeout. */
    boolean await(String topic, long offset, long timeoutMs) throws InterruptedException {
        var t = topic(topic);
        long left = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        t.lock.lock();
        try {
            while (t.acked < offset) {
                if (left <= 0) return false;
                left = t.advanced.awaitNanos(left);
            }
            return true;
        } finally {
            t.lock.unlock();
        }
    }
}
