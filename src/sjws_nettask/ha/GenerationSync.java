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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import sjws_nettask.cluster.ClusterNode;
import sjws_nettask.cluster.CommittedIndex;
import sjws_nettask.cluster.GroupId;
import sjws_nettask.cluster.GroupOptions;

/**
 * Two-phase publish on {@code ha-control}: STAGE commits the content hash, peers pull the
 * HMAC bundle and write the key mode {@code 0600}, then ACTIVATE commits. The HTTP request
 * path never calls {@link ClusterNode#append}.
 */
public final class GenerationSync implements AutoCloseable {

    static final byte STAGE = 1;
    static final byte ACTIVATE = 2;

    private final ClusterNode node;
    private final GroupId group;
    private final HmacBundles bundles;
    private final byte[] secret;
    private final Path keyFile;
    private final Consumer<Long> onActivate;
    /**
     * STAGE pulls the bundle and writes the key (IO), so it never runs in the commit callback: the callback
     * only hands each marker to this single worker, which keeps STAGE before its ACTIVATE.
     */
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors
            .newSingleThreadExecutor(Thread.ofVirtual().name("ha-generation-sync").factory());

    public GenerationSync(ClusterNode node, GroupId group, GroupOptions options, HmacBundles bundles, byte[] secret,
            Path keyFile, Consumer<Long> onActivate) {
        this.node = Objects.requireNonNull(node, "node");
        this.group = Objects.requireNonNull(group, "group");
        this.bundles = Objects.requireNonNull(bundles, "bundles");
        this.secret = secret.clone();
        this.keyFile = Objects.requireNonNull(keyFile, "keyFile");
        this.onActivate = onActivate == null ? generation -> {
        } : onActivate;
        node.openGroup(group, options, entry -> handOff(entry.entry()));
    }

    public CompletableFuture<CommittedIndex> publishFrom(long generation, ConfigSource config, CertProvider certs)
            throws IOException {
        return publish(generation, config.load(), certs.certificate(), certs.privateKey());
    }

    /**
     * STAGE, then ACTIVATE once STAGE commits. Never retried here: a failure with
     * {@link sjws_nettask.cluster.NotLeaderException} means the failed marker was refused, while
     * {@link sjws_nettask.cluster.OutcomeUnknownException} means it may still commit (a STAGE without its ACTIVATE
     * is harmless; an ACTIVATE may yet take effect). A caller re-publishes the same generation only after reading
     * the group back (#254).
     */
    public CompletableFuture<CommittedIndex> publish(long generation, byte[] config, byte[] certificate,
            byte[] privateKey) {
        var payload = payload(config, certificate, privateKey);
        bundles.publish(generation, payload, secret);
        var hash = sha256(payload);
        return node.append(group, List.of(ByteBuffer.wrap(marker(STAGE, generation, hash)))).thenCompose(staged -> node
                .append(group, List.of(ByteBuffer.wrap(marker(ACTIVATE, generation, hash)))));
    }

    /** The commit callback: copy the marker and return; no IO here (GroupListener rules). */
    private void handOff(ByteBuffer entry) {
        var copy = new byte[entry.remaining()];
        entry.duplicate().get(copy);
        try {
            worker.execute(() -> {
                try {
                    apply(copy);
                } catch (RuntimeException failed) {
                    System.getLogger(GenerationSync.class.getName()).log(System.Logger.Level.WARNING,
                            "generation sync failed: " + failed.getMessage(), failed);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            // Closed: later markers are not applied.
        }
    }

    /** Waits until every marker handed off so far has been applied (tests). */
    boolean awaitApplied(java.time.Duration timeout) throws InterruptedException {
        var done = new java.util.concurrent.CountDownLatch(1);
        try {
            worker.execute(done::countDown);
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            return false;
        }
        return done.await(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** How long {@link #close()} waits for markers already handed off. */
    static final java.time.Duration CLOSE_WAIT = java.time.Duration.ofSeconds(2);

    /**
     * Stops the worker after the markers already handed off, waiting up to {@link #CLOSE_WAIT}. A STAGE still
     * running past that is safe: the key file is written to a temp file and renamed into place atomically, so it
     * is either the old key or the new one, never partial.
     */
    @Override
    public void close() {
        closeWithin(CLOSE_WAIT);
    }

    /** {@link #close()} with an explicit wait; true when the worker finished in time. */
    boolean closeWithin(java.time.Duration wait) {
        worker.shutdown();
        try {
            if (worker.awaitTermination(wait.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.getLogger(GenerationSync.class.getName()).log(System.Logger.Level.WARNING,
                "generation sync still applying after " + wait.toMillis() + " ms at close");
        return false;
    }

    /** Runs {@code task} on the sync worker, after every marker handed off so far (tests). */
    void onWorker(Runnable task) {
        worker.execute(task);
    }

    void apply(byte[] copy) {
        if (copy.length < 1 + 8 + 32) {
            return;
        }
        var body = ByteBuffer.wrap(copy);
        byte kind = body.get();
        long generation = body.getLong();
        if (kind == STAGE) {
            try {
                var payload = bundles.pull(generation, secret);
                writeKey(privateKey(payload));
            } catch (IOException failed) {
                throw new IllegalStateException(failed);
            }
        } else if (kind == ACTIVATE) {
            onActivate.accept(generation);
        }
    }

    private void writeKey(byte[] key) throws IOException {
        var dir = keyFile.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        var tmp = Files.createTempFile(dir == null ? Path.of(".") : dir, "key", ".tmp");
        try {
            Files.write(tmp, key);
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            try {
                Files.move(tmp, keyFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(tmp, keyFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    static byte[] payload(byte[] config, byte[] certificate, byte[] key) {
        var body = ByteBuffer.allocate(12 + config.length + certificate.length + key.length);
        put(body, config);
        put(body, certificate);
        put(body, key);
        return body.array();
    }

    static byte[] privateKey(byte[] payload) {
        var body = ByteBuffer.wrap(payload);
        skip(body);
        skip(body);
        return read(body);
    }

    private static void put(ByteBuffer body, byte[] bytes) {
        body.putInt(bytes.length);
        body.put(bytes);
    }

    private static void skip(ByteBuffer body) {
        int len = body.getInt();
        body.position(body.position() + len);
    }

    private static byte[] read(ByteBuffer body) {
        int len = body.getInt();
        var out = new byte[len];
        body.get(out);
        return out;
    }

    private static byte[] marker(byte kind, long generation, byte[] hash) {
        var body = ByteBuffer.allocate(1 + 8 + hash.length);
        body.put(kind);
        body.putLong(generation);
        body.put(hash);
        return body.array();
    }

    private static byte[] sha256(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException failed) {
            throw new IllegalStateException(failed);
        }
    }
}
