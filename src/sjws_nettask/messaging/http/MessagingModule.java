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

import java.nio.file.Path;

import sjws_nettask.OptionalModule;
import sjws_nettask.core.HandlerContributor;
import sjws_nettask.core.HandlerMounts;
import sjws_nettask.messaging.ledger.LedgerWriter;
import sjws_nettask.messaging.ledger.SegmentedLog;

/**
 * Mounts produce / fetch on plain log topics at {@code /messaging/v1} (docs/design/partition-log.md §2.2). Off by
 * default: loaded by name from {@code OptionalModules} only when {@code sjws.messaging.enabled} is true.
 */
public final class MessagingModule implements OptionalModule, HandlerContributor {

    static final String DIR_PROPERTY = "sjws.messaging.dir";
    static final String DIR_ENV = "SJWS_MESSAGING_DIR";

    static final String SEGMENT_BYTES_PROPERTY = "sjws.messaging.segment.bytes";
    static final String SEGMENT_BYTES_ENV = "SJWS_MESSAGING_SEGMENT_BYTES";
    static final String RETENTION_BYTES_PROPERTY = "sjws.messaging.retention.bytes";
    static final String RETENTION_BYTES_ENV = "SJWS_MESSAGING_RETENTION_BYTES";
    static final String RETENTION_MS_PROPERTY = "sjws.messaging.retention.ms";
    static final String RETENTION_MS_ENV = "SJWS_MESSAGING_RETENTION_MS";
    static final String RETENTION_CHECK_MS_PROPERTY = "sjws.messaging.retention.check.ms";
    static final String RETENTION_CHECK_MS_ENV = "SJWS_MESSAGING_RETENTION_CHECK_MS";

    private final LogTopics topics;
    private volatile MessagingHooks hooks = MessagingHooks.NONE;
    /** Null unless a retention limit is set: no thread when retention is off. */
    private final java.util.concurrent.ScheduledExecutorService retentionTimer;

    public MessagingModule() {
        this(Path.of(setting(DIR_PROPERTY, DIR_ENV, "messaging-data")),
                number(setting(SEGMENT_BYTES_PROPERTY, SEGMENT_BYTES_ENV, ""), SegmentedLog.DEFAULT_SEGMENT_BYTES),
                new LogRetention(number(setting(RETENTION_BYTES_PROPERTY, RETENTION_BYTES_ENV, ""), 0),
                        number(setting(RETENTION_MS_PROPERTY, RETENTION_MS_ENV, ""), 0)),
                number(setting(RETENTION_CHECK_MS_PROPERTY, RETENTION_CHECK_MS_ENV, ""), 10_000));
    }

    MessagingModule(Path dir) {
        this(dir, SegmentedLog.DEFAULT_SEGMENT_BYTES, LogRetention.NONE, 10_000);
    }

    MessagingModule(Path dir, long segmentBytes, LogRetention retention, long checkMs) {
        this.topics = new LogTopics(dir, LedgerWriter.DEFAULT_DURABILITY, segmentBytes, retention, System::currentTimeMillis);
        if (retention.unlimited()) {
            this.retentionTimer = null;
        } else {
            this.retentionTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    r -> Thread.ofPlatform().name("messaging-retention").daemon().unstarted(r));
            this.retentionTimer.scheduleWithFixedDelay(this::retain, checkMs, checkMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    void retain() {
        try {
            this.topics.enforceRetention();
        } catch (java.io.IOException | RuntimeException e) {
            System.err.println("WARN: messaging retention: " + e);
        }
    }

    boolean retentionScheduled() {
        return this.retentionTimer != null;
    }

    static long number(String v, long dflt) {
        if (v.isEmpty()) return dflt;
        try {
            long n = Long.parseLong(v);
            return n < 0 ? dflt : n;
        } catch (NumberFormatException e) {
            System.err.println("WARN: messaging setting not a number: " + v);
            return dflt;
        }
    }

    static String setting(String property, String env, String dflt) {
        var v = System.getProperty(property);
        if (v == null || v.isBlank()) v = System.getenv(env);
        return v == null || v.isBlank() ? dflt : v.trim();
    }

    /** Installs the metrics hooks (see {@link MessagingHooks}); call before the server mounts the module. */
    public void hooks(MessagingHooks h) {
        this.hooks = java.util.Objects.requireNonNull(h);
    }

    @Override
    public void contribute(HandlerMounts mounts) {
        var h = new MessagingHttp(this.topics);
        h.hooks(this.hooks);
        mounts.mount("/messaging/v1", h);
    }

    @Override
    public void close() {
        if (this.retentionTimer != null) this.retentionTimer.shutdownNow();
        this.topics.close();
    }
}
