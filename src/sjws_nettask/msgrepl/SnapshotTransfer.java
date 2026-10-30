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
package sjws_nettask.msgrepl;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import javax.crypto.KDF;
import javax.crypto.Mac;
import javax.crypto.spec.HKDFParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Install-snapshot data transfer for {@code msg/*} groups (#270). The consensus snapshot itself is only the small
 * {@link MsgSnapshot} manifest (sent as a chunked Raft snapshot, HMAC per frame); the segment bytes travel on this side
 * channel, one TCP connection per install, from any data node that holds a pin at or above the index asked for.
 *
 * <p><b>Trust (#277).</b> Every request is authenticated with HMAC-SHA256 under {@code HKDF-SHA256(ha.cluster_key,
 * info "sjws-msg-snapshot-v1")} (the rotation key is accepted too), carries a fresh nonce and a timestamp checked within
 * {@link #SKEW_MS}, and a nonce seen before is refused. The server's manifest is MACed under the same key bound to both
 * nonces, so a client only trusts a manifest this cluster produced for this request. Segment bytes are not MACed: the
 * server sends them with {@link FileChannel#transferTo} (sendfile, zero-copy, constraint 7), and the client checks every
 * chunk against the SHA-256 in the authenticated manifest before writing it. Like the Raft frames, the channel is
 * authenticated but not encrypted: keep the member network private.
 *
 * <p><b>Bounds.</b> Topic names are plain topic names; files are only ever named from numeric segment bases; the
 * manifest is at most {@code maxManifest} bytes and a chunk at most {@link MsgSnapshot#MAX_CHUNK}; at most
 * {@link #MAX_SESSIONS} transfers run at once, each with a read timeout; the server paces all of them with one token
 * bucket ({@code bytesPerSecond}), so an install never takes the leader's whole disk or network. {@code maxInstallBytes}
 * (default {@link Snapshots#DEFAULT_MAX_INSTALL_BYTES}, {@code 0} = unlimited) is checked on the authenticated
 * manifest before any chunk is requested and again before each chunk is written (#295). An optional {@code accept}
 * predicate compares the manifest to the consensus image before chunk bodies are fetched (#295).
 *
 * <p>Wire (big-endian). Hello: {@code "SJMS" v=1 nonceC[16] tsMs topicLen:u8 topic minIndex mac[32]}. Reply:
 * {@code status:u8 nonceS[16] pinIndex len:int manifest mac[32]} (status 1: no pin, then close). Chunk request:
 * {@code seq base chunk:int mac[32]} (seq strictly increasing; base -1 ends). Chunk reply: {@code status:u8 len:int
 * bytes}.
 */
final class SnapshotTransfer {

    static final int MAGIC = 0x534A4D53; // "SJMS"
    static final byte VERSION = 1;
    static final byte[] INFO = "sjws-msg-snapshot-v1".getBytes(StandardCharsets.US_ASCII);
    static final long SKEW_MS = 120_000;
    static final int MAX_SESSIONS = 4;
    static final int TIMEOUT_MS = 15_000;

    /**
     * Socket read/connect deadline that stays ahead of one paced chunk at {@code bytesPerSecond}, with a floor of
     * {@link #TIMEOUT_MS}. Slow {@code ha.snapshot.install_rate} must not time out a legitimate transfer (#288 Codex P2).
     */
    static int timeoutMs(long bytesPerSecond, int chunkBytes) {
        if (bytesPerSecond < 1) bytesPerSecond = 1;
        if (chunkBytes < 1) chunkBytes = MsgSnapshot.MIN_CHUNK;
        long chunkMs = (chunkBytes * 1000L + bytesPerSecond - 1) / bytesPerSecond + 5_000L;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(TIMEOUT_MS, chunkMs));
    }
    static final int NONCE = 16;
    static final int MAC = 32;

    private SnapshotTransfer() {
    }

    /** {@code HKDF-SHA256(clusterKey, info "sjws-msg-snapshot-v1")}, 32 bytes. Not a key for any other purpose. */
    static byte[] deriveKey(byte[] clusterKey) {
        Objects.requireNonNull(clusterKey, "cluster key");
        try {
            var kdf = KDF.getInstance("HKDF-SHA256");
            var params = HKDFParameterSpec.ofExtract().addIKM(clusterKey).thenExpand(INFO, 32);
            return kdf.deriveData(params);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HKDF-SHA256 unavailable", e);
        }
    }

    static Mac mac(byte[] key) {
        try {
            var m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] tag(byte[] key, byte[]... parts) {
        var m = mac(key);
        for (var p : parts) m.update(p);
        return m.doFinal();
    }

    /** The key (current or rotation) that {@code got} verifies under, or null. */
    static byte[] verify(byte[][] keys, byte[] got, byte[]... parts) {
        for (var k : keys) {
            if (k != null && MessageDigest.isEqual(tag(k, parts), got)) return k;
        }
        return null;
    }

    /** Paces bytes across every transfer of a server: a token bucket with one second of burst. */
    static final class RateLimiter {
        private final long bytesPerSecond;
        private final ReentrantLock lock = new ReentrantLock();
        private long tokens;
        private long last = System.nanoTime();

        RateLimiter(long bytesPerSecond) {
            if (bytesPerSecond < 1) throw new IllegalArgumentException("bytesPerSecond");
            this.bytesPerSecond = bytesPerSecond;
            this.tokens = bytesPerSecond;
        }

        void acquire(long n) throws InterruptedException {
            while (true) {
                long wait;
                lock.lock();
                try {
                    long now = System.nanoTime();
                    // Cap elapsed before multiplying: a long idle at high bytesPerSecond overflows and
                    // can leave tokens negative (#288 Codex P2).
                    long elapsed = Math.min(now - last, 1_000_000_000L);
                    tokens = Math.min(bytesPerSecond, tokens + elapsed * bytesPerSecond / 1_000_000_000L);
                    last = now;
                    if (tokens >= n || tokens == bytesPerSecond) {
                        tokens -= n;
                        return;
                    }
                    wait = (n - tokens) * 1_000_000_000L / bytesPerSecond;
                } finally {
                    lock.unlock();
                }
                java.util.concurrent.locks.LockSupport.parkNanos(Math.max(wait, 100_000));
                if (Thread.interrupted()) throw new InterruptedException();
            }
        }
    }

    /** Serves pins from a {@link SnapshotStore}. */
    static final class Server implements AutoCloseable {
        private final ServerSocketChannel listener;
        private final byte[][] keys;
        private final SnapshotStore store;
        private final RateLimiter limiter;
        private final long bytesPerSecond;
        private final LongSupplier clockMs;
        private final Semaphore sessions = new Semaphore(MAX_SESSIONS);
        private final Map<String, Boolean> nonces = new LinkedHashMap<>(1024, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                return size() > 8192;
            }
        };
        private final ReentrantLock nonceLock = new ReentrantLock();
        private final Thread acceptor;
        private volatile boolean closed;
        final AtomicLong refused = new AtomicLong();
        /** Test-only: throw after acquire, before manifest write (#288 Codex P2). */
        volatile boolean failManifestWrite;
        final AtomicLong bytesSent = new AtomicLong();
        final AtomicLong chunksSent = new AtomicLong();

        Server(InetSocketAddress bind, byte[] key, byte[] previous, SnapshotStore store, long bytesPerSecond,
                LongSupplier clockMs) throws IOException {
            this(bindChannel(bind), key, previous, store, bytesPerSecond, clockMs);
        }

        /**
         * Uses a pre-bound {@code listener} (port already reserved). Callers that share the port map across nodes
         * should bind with {@link ServerSocketChannel#bind} to port 0 and keep the channel until this ctor runs,
         * so another process cannot steal the port between reservation and listen (#288 MC-N3).
         */
        Server(ServerSocketChannel listener, byte[] key, byte[] previous, SnapshotStore store, long bytesPerSecond,
                LongSupplier clockMs) throws IOException {
            this.keys = new byte[][] {key, previous};
            this.store = store;
            this.limiter = new RateLimiter(bytesPerSecond);
            this.bytesPerSecond = bytesPerSecond;
            this.clockMs = clockMs;
            this.listener = listener;
            this.acceptor = Thread.ofPlatform().daemon().name("msgrepl-snapshot-server").start(this::acceptLoop);
        }

        private static ServerSocketChannel bindChannel(InetSocketAddress bind) throws IOException {
            var ch = ServerSocketChannel.open();
            try {
                ch.bind(bind, 16);
                return ch;
            } catch (IOException e) {
                closeQuietly(ch);
                throw e;
            }
        }

        InetSocketAddress address() throws IOException {
            return (InetSocketAddress) listener.getLocalAddress();
        }

        private void acceptLoop() {
            while (!closed) {
                SocketChannel sc;
                try {
                    sc = listener.accept();
                } catch (IOException e) {
                    if (!closed) System.err.println("WARN: msgrepl snapshot server: accept: " + e);
                    return;
                }
                if (!sessions.tryAcquire()) {
                    refused.incrementAndGet();
                    closeQuietly(sc);
                    continue;
                }
                Thread.ofVirtual().name("msgrepl-snapshot-session").start(() -> {
                    try {
                        serve(sc);
                    } catch (IOException | RuntimeException | InterruptedException e) {
                        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    } finally {
                        closeQuietly(sc);
                        sessions.release();
                    }
                });
            }
        }

        private boolean fresh(byte[] nonce) {
            var k = java.util.HexFormat.of().formatHex(nonce);
            nonceLock.lock();
            try {
                return nonces.putIfAbsent(k, Boolean.TRUE) == null;
            } finally {
                nonceLock.unlock();
            }
        }

        private void serve(SocketChannel sc) throws IOException, InterruptedException {
            sc.socket().setSoTimeout(timeoutMs(bytesPerSecond, MsgSnapshot.MAX_CHUNK));
            sc.socket().setTcpNoDelay(true);
            var in = new DataInputStream(sc.socket().getInputStream());
            var head = new byte[4 + 1 + NONCE + 8 + 1];
            in.readFully(head);
            var hb = ByteBuffer.wrap(head);
            if (hb.getInt() != MAGIC || hb.get() != VERSION) {
                refused.incrementAndGet();
                return;
            }
            var nonceC = new byte[NONCE];
            hb.get(nonceC);
            long ts = hb.getLong();
            int tl = hb.get() & 0xFF;
            if (tl < 1 || tl > 128) {
                refused.incrementAndGet();
                return;
            }
            var tb = new byte[tl];
            in.readFully(tb);
            long minIndex = in.readLong();
            var mac = new byte[MAC];
            in.readFully(mac);
            var tail = ByteBuffer.allocate(8).putLong(minIndex).array();
            var topic = new String(tb, StandardCharsets.UTF_8);
            var key = verify(keys, mac, head, tb, tail);
            if (key == null || Math.abs(clockMs.getAsLong() - ts) > SKEW_MS || !fresh(nonceC)
                    || !MsgSnapshot.TOPIC.matcher(topic).matches() || topic.equals(".") || topic.equals("..")) {
                refused.incrementAndGet();
                return; // no answer: an unauthenticated peer learns nothing, not even whether the topic exists
            }
            var pin = store.acquire(topic, minIndex);
            try {
                var nonceS = new byte[NONCE];
                RANDOM.nextBytes(nonceS);
                byte status = (byte) (pin == null ? 1 : 0);
                long pinIndex = pin == null ? -1 : pin.index;
                var manifest = pin == null ? new byte[0] : pin.manifest;
                var reply = ByteBuffer.allocate(1 + NONCE + 8 + 4).put(status).put(nonceS).putLong(pinIndex).putInt(manifest.length).array();
                var rmac = tag(key, new byte[] {'M'}, nonceC, reply, manifest); // the key the client proved it holds
                // Manifest write is inside the pin-release finally: a disconnect here must not leak readers (#288 Codex P2).
                if (failManifestWrite) throw new IOException("test: fail manifest write");
                write(sc, ByteBuffer.wrap(reply), ByteBuffer.wrap(manifest), ByteBuffer.wrap(rmac));
                if (pin == null) return;
                var snap = MsgSnapshot.decode(pin.manifest);
                long lastSeq = 0;
                while (true) {
                    long seq = in.readLong(), base = in.readLong();
                    int chunk = in.readInt();
                    in.readFully(mac);
                    var req = ByteBuffer.allocate(20).putLong(seq).putLong(base).putInt(chunk).array();
                    if (!MessageDigest.isEqual(tag(key, new byte[] {'C'}, nonceS, req), mac) || seq <= lastSeq) {
                        refused.incrementAndGet();
                        return;
                    }
                    lastSeq = seq;
                    if (base == -1) return;
                    MsgSnapshot.Seg seg = null;
                    for (var s : snap.segments()) if (s.base() == base) seg = s;
                    if (seg == null || chunk < 0 || chunk >= seg.digests().length) {
                        write(sc, ByteBuffer.allocate(5).put((byte) 1).putInt(0).flip());
                        return;
                    }
                    long pos = (long) chunk * snap.chunkBytes();
                    int len = (int) Math.min(snap.chunkBytes(), seg.bytes() - pos);
                    limiter.acquire(len);
                    write(sc, ByteBuffer.allocate(5).put((byte) 0).putInt(len).flip());
                    try (var fc = FileChannel.open(pin.dir.resolve(SnapshotStore.name(base)), StandardOpenOption.READ)) {
                        long sent = 0;
                        while (sent < len) {
                            long n = fc.transferTo(pos + sent, len - sent, sc); // sendfile: the bytes never enter the heap
                            if (n <= 0) throw new IOException("short transfer");
                            sent += n;
                        }
                    }
                    bytesSent.addAndGet(len);
                    chunksSent.incrementAndGet();
                }
            } finally {
                if (pin != null) store.release(pin);
            }
        }

        @Override
        public void close() {
            closed = true;
            closeQuietly(listener);
            acceptor.interrupt();
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    static void write(SocketChannel sc, ByteBuffer... bufs) throws IOException {
        long left = 0;
        for (var b : bufs) left += b.remaining();
        while (left > 0) left -= sc.write(bufs);
    }

    static void closeQuietly(java.io.Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // closing
        }
    }

    /** A pin that a peer has no longer, or a peer that refused or failed: try the next one. */
    static final class Unavailable extends IOException {
        Unavailable(String m) {
            super(m);
        }
    }

    /**
     * The peer's pin does not match the consensus snapshot image. Thrown after the authenticated manifest is read and
     * before any chunk body is requested, so a divergent or superseded pin wastes no transfer bandwidth (#295).
     */
    static final class Divergent extends IOException {
        Divergent(String m) {
            super(m);
        }
    }

    /**
     * The install would exceed {@code ha.snapshot.max_install_bytes}. Thrown before writing the overflowing bytes
     * (manifest check first, then per-chunk) so staging is not left with a partial oversized image (#295).
     */
    static final class Oversized extends IOException {
        Oversized(String m) {
            super(m);
        }
    }

    /**
     * Fetches the newest pin of {@code topic} at or above {@code minIndex} from {@code peer} into {@code staging}
     * ({@code <base>.log} files and {@code <base>.compacted} markers, fsynced). Chunks already in {@code staging} whose
     * digest matches are kept, so a transfer cut short (or a crash) resumes. Returns the verified manifest.
     */
    static MsgSnapshot fetch(InetSocketAddress peer, byte[] key, String topic, long minIndex, Path staging, int maxManifest,
            LongSupplier clockMs) throws IOException {
        return fetch(peer, key, topic, minIndex, staging, maxManifest, clockMs, 64L << 20, MsgSnapshot.MIN_CHUNK, 0L, null);
    }

    static MsgSnapshot fetch(InetSocketAddress peer, byte[] key, String topic, long minIndex, Path staging, int maxManifest,
            LongSupplier clockMs, long bytesPerSecond, int chunkBytes) throws IOException {
        return fetch(peer, key, topic, minIndex, staging, maxManifest, clockMs, bytesPerSecond, chunkBytes, 0L, null);
    }

    /**
     * As {@link #fetch(InetSocketAddress, byte[], String, long, Path, int, LongSupplier, long, int)} with an install
     * size bound and an optional consensus-image check (#295).
     *
     * @param maxInstallBytes  refuse when the manifest's {@link MsgSnapshot#dataBytes()} exceeds this, and again before
     *                         writing any chunk that would push the received total over it; {@code 0} leaves it off
     * @param accept           when non-null, the authenticated manifest must satisfy it before any chunk is requested;
     *                         a refusal throws {@link Divergent} and downloads nothing
     */
    static MsgSnapshot fetch(InetSocketAddress peer, byte[] key, String topic, long minIndex, Path staging, int maxManifest,
            LongSupplier clockMs, long bytesPerSecond, int chunkBytes, long maxInstallBytes, Predicate<MsgSnapshot> accept)
            throws IOException {
        if (maxInstallBytes < 0) throw new IllegalArgumentException("maxInstallBytes");
        Files.createDirectories(staging);
        int timeout = timeoutMs(bytesPerSecond, chunkBytes);
        try (var sc = SocketChannel.open()) {
            sc.socket().connect(peer, timeout);
            sc.socket().setSoTimeout(timeout);
            sc.socket().setTcpNoDelay(true);
            var in = new DataInputStream(sc.socket().getInputStream());
            var nonceC = new byte[NONCE];
            RANDOM.nextBytes(nonceC);
            var tb = topic.getBytes(StandardCharsets.UTF_8);
            var head = ByteBuffer.allocate(4 + 1 + NONCE + 8 + 1).putInt(MAGIC).put(VERSION).put(nonceC)
                    .putLong(clockMs.getAsLong()).put((byte) tb.length).array();
            var tail = ByteBuffer.allocate(8).putLong(minIndex).array();
            write(sc, ByteBuffer.wrap(head), ByteBuffer.wrap(tb), ByteBuffer.wrap(tail), ByteBuffer.wrap(tag(key, head, tb, tail)));
            var reply = new byte[1 + NONCE + 8 + 4];
            in.readFully(reply);
            var rb = ByteBuffer.wrap(reply);
            byte status = rb.get();
            var nonceS = new byte[NONCE];
            rb.get(nonceS);
            long pinIndex = rb.getLong();
            int len = rb.getInt();
            if (len < 0 || len > maxManifest) throw new IOException("manifest of " + len + " bytes refused");
            var manifest = new byte[len];
            in.readFully(manifest);
            var mac = new byte[MAC];
            in.readFully(mac);
            if (!MessageDigest.isEqual(tag(key, new byte[] {'M'}, nonceC, reply, manifest), mac)) {
                throw new IOException("snapshot manifest fails its MAC");
            }
            if (status != 0) throw new Unavailable("no snapshot of " + topic + " at or above " + minIndex + " on " + peer);
            var snap = MsgSnapshot.decode(manifest);
            if (!snap.topic().equals(topic) || snap.index() != pinIndex || snap.index() < minIndex) {
                throw new IOException("snapshot manifest does not match the request");
            }
            // #295 (c): compare the consensus image before requesting any chunk body.
            if (accept != null && !accept.test(snap)) {
                throw new Divergent("pin at " + snap.index() + " does not match the consensus snapshot");
            }
            // #295 (a): refuse on the authenticated manifest size before any chunk hits the wire or disk.
            if (maxInstallBytes > 0 && snap.dataBytes() > maxInstallBytes) {
                throw new Oversized("install of " + snap.dataBytes() + " bytes exceeds ha.snapshot.max_install_bytes "
                        + maxInstallBytes);
            }
            long seq = 0;
            long received = 0;
            var buf = ByteBuffer.allocate(snap.chunkBytes());
            try {
                for (var seg : snap.segments()) {
                    var file = staging.resolve(SnapshotStore.name(seg.base())); // named from the number only
                    try (var out = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                        if (out.size() > seg.bytes()) out.truncate(seg.bytes());
                        for (int c = 0; c < seg.digests().length; c++) {
                            long pos = (long) c * snap.chunkBytes();
                            int clen = (int) Math.min(snap.chunkBytes(), seg.bytes() - pos);
                            if (out.size() >= pos + clen && MessageDigest.isEqual(SnapshotStore.digest(out, pos, clen, buf), seg.digests()[c])) {
                                received += clen;
                                continue; // resumed: already here and intact
                            }
                            var req = ByteBuffer.allocate(20).putLong(++seq).putLong(seg.base()).putInt(c).array();
                            write(sc, ByteBuffer.wrap(req), ByteBuffer.wrap(tag(key, new byte[] {'C'}, nonceS, req)));
                            if (in.readByte() != 0) throw new IOException("peer refused chunk " + c + " of segment " + seg.base());
                            int got = in.readInt();
                            if (got != clen) throw new IOException("chunk of " + got + " bytes, expected " + clen);
                            buf.clear().limit(clen);
                            in.readFully(buf.array(), 0, clen);
                            var md = SnapshotStore.sha256();
                            md.update(buf.array(), 0, clen);
                            if (!MessageDigest.isEqual(md.digest(), seg.digests()[c])) {
                                throw new IOException("chunk " + c + " of segment " + seg.base() + " fails its SHA-256");
                            }
                            // #295 (a): bound before the write so an overflow leaves no new bytes on disk.
                            if (maxInstallBytes > 0 && received + clen > maxInstallBytes) {
                                throw new Oversized("install would exceed ha.snapshot.max_install_bytes " + maxInstallBytes
                                        + " after " + received + " bytes");
                            }
                            while (buf.hasRemaining()) out.write(buf, pos + buf.position());
                            received += clen;
                        }
                        if (out.size() != seg.bytes()) out.truncate(seg.bytes());
                        out.force(true);
                    }
                    var marker = staging.resolve(String.format("%020d.compacted", seg.base()));
                    if (seg.markerNext() >= 0) {
                        Files.write(marker, ByteBuffer.allocate(8).putLong(seg.markerNext()).array());
                        try (var m = FileChannel.open(marker, StandardOpenOption.WRITE)) {
                            m.force(true);
                        }
                    } else {
                        Files.deleteIfExists(marker);
                    }
                }
            } catch (Oversized e) {
                clearStagingQuietly(staging);
                throw e;
            }
            var end = ByteBuffer.allocate(20).putLong(++seq).putLong(-1).putInt(0).array();
            write(sc, ByteBuffer.wrap(end), ByteBuffer.wrap(tag(key, new byte[] {'C'}, nonceS, end)));
            // Nothing else may be installed with it: files of other bases left from an older attempt go.
            try (var list = Files.list(staging)) {
                for (var p : (Iterable<Path>) list::iterator) {
                    var n = p.getFileName().toString();
                    boolean keep = false;
                    for (var seg : snap.segments()) {
                        keep |= n.equals(SnapshotStore.name(seg.base())) || n.equals(String.format("%020d.compacted", seg.base()));
                    }
                    if (!keep) Files.delete(p);
                }
            }
            SnapshotStore.fsyncDir(staging);
            return snap;
        }
    }

    /** Drops staging contents after an oversized abort so no partial install remains (#295). */
    private static void clearStagingQuietly(Path staging) {
        try {
            if (!Files.isDirectory(staging)) return;
            try (var stream = Files.list(staging)) {
                stream.forEach(f -> {
                    try {
                        Files.deleteIfExists(f);
                    } catch (IOException ignored) {
                    }
                });
            }
        } catch (IOException ignored) {
        }
    }
}
