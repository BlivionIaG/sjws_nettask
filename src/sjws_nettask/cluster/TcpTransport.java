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
package sjws_nettask.cluster;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import javax.crypto.KDF;
import javax.crypto.Mac;
import javax.crypto.spec.HKDFParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Raft frames over TCP, one outbound connection per peer. Each frame is length-prefixed and
 * carries the same authenticated fields as {@link HeartbeatAuthenticator}: version, key id,
 * sender and destination wire ids, a per-boot epoch, a per-sender sequence, then an
 * HMAC-SHA256 tag:
 *
 * <pre>
 * int length (of everything after it)
 * byte version, byte keyId, short from, short dest, long bootEpoch, long seq,
 * int type, short groupLength, group UTF-8, body, 32-byte tag
 * </pre>
 *
 * The tag input is {@code "sjws-raft-frame\0" || every byte before the tag}. The key is
 * HKDF-SHA256 of the {@link ClusterKey} with info {@code sjws-raft-frame-v1} (the {@link Domain#RAFT} domain;
 * another {@link Domain} changes both, as the swarm peer links do), so it is not the
 * heartbeat key. A frame for another destination, from a non-member, with a bad tag, an older
 * epoch or a sequence already seen is dropped and counted by {@link Reason}.
 *
 * <p>The Raft thread only calls {@link ClusterRpc#send}, which offers into a bounded per-peer
 * queue and counts a drop when it is full. Writer and reader threads are virtual. Inbound
 * frames wait in a bounded queue until {@link #deliver} runs them on the Raft thread. Every
 * socket comes from one {@link SocketFactory}, which is where mTLS plugs in.
 *
 * <p>Pre-authentication limits: accepted sockets get a read timeout ({@link #READ_TIMEOUT_MILLIS}
 * by default) that bounds both idle time between frames and the time to receive one whole frame,
 * so a slowloris sender is closed; the whole-frame deadline starts at the first length-prefix byte and a frame
 * that misses it is counted as {@link Reason#TIMEOUT}. A connection whose source address is not a member's
 * address is closed at accept and counted in {@link #strangersRefused}, with or without mTLS. An accepted socket
 * then completes {@link SocketFactory#handshake} within {@link #HANDSHAKE_TIMEOUT_MILLIS} in total before it
 * counts as an inbound slot, so a stalled handshake cannot hold one; at most {@code 8 x peers} handshakes run at
 * once, and an accept beyond that is closed and counted in {@link #handshakesRefused}. At most {@code 2 x peers} inbound connections are open at once;
 * extra ones are closed at accept and counted in {@link #inboundRefused}. A frame body is read into
 * a buffer that starts at {@link #PREAUTH_CHUNK_BYTES} and grows with the bytes actually received,
 * so a large length prefix alone does not allocate. An idle follower-to-follower link that the
 * receiver timed out costs at most one dropped frame before the writer reconnects.
 *
 * <p>Sequence numbers come from one counter shared by all peers; the receiver tracks the high-water
 * mark per sender, and ordering relies on exactly one TCP stream per peer. The writer always seals
 * with the current key while readers also accept the previous one, so a rotation must reach every
 * node (accepting the new key) before any node sends with it. The boot epoch is wall-clock
 * milliseconds, like heartbeats: a restart within the same millisecond, or after a backwards clock
 * step with under {@link #EPOCH_SILENCE_MILLIS} of silence, is refused until that silence floor.
 * When Security swaps in an mTLS {@link SocketFactory}, the handshake must run on the reader thread
 * (not the acceptor) and is covered by the same read timeout.
 */
public final class TcpTransport implements ClusterTransport, AutoCloseable {

    public static final int VERSION = 1;
    public static final int MAX_FRAME_BYTES = 16 << 20;
    public static final int MAC_LENGTH = 32;
    /** A lower boot epoch is accepted only after the sender was silent this long. */
    public static final long EPOCH_SILENCE_MILLIS = AuthReplay.WINDOW_MILLIS;
    private static final int HEADER_BYTES = 1 + 1 + 2 + 2 + 8 + 8 + 4 + 2;
    private final byte[] label;
    private final byte[] hkdfInfo;
    private final Domain domain;
    private final boolean direct;
    private final int maxFrameBytes;
    private static final long RECONNECT_MILLIS = 50;
    private static final int CONNECT_TIMEOUT_MILLIS = 1_000;
    /** Default idle and whole-frame read deadline on accepted sockets. */
    public static final int READ_TIMEOUT_MILLIS = 10_000;
    /** Total deadline for an accepted socket's {@link SocketFactory#handshake}; it holds no slot meanwhile. */
    public static final int HANDSHAKE_TIMEOUT_MILLIS = 5_000;
    /** Largest step the pre-auth read buffer grows by. */
    static final int PREAUTH_CHUNK_BYTES = 64 << 10;

    /** Why an inbound frame was dropped. */
    public enum Reason {
        MALFORMED,
        TAG,
        DEST,
        MEMBER,
        EPOCH,
        SEQ,
        /** The whole-frame deadline passed after the first length-prefix byte (slow or stalled sender). */
        TIMEOUT,
        /**
         * A correctly tagged frame whose sender wire id is not the one the connection's authenticated identity pins
         * ({@link SocketFactory#peerWire}); the connection is closed. Never counted when the factory pins nothing.
         */
        ORIGIN
    }

    /**
     * Which protocol a transport carries. The tag label and the HKDF info are domain-separated, so a frame sealed
     * for one domain (Raft, the swarm peer links of #236) fails the tag check in every other one, even under the
     * same key. {@link #RAFT} is the Raft transport's domain, unchanged.
     *
     * @param name thread-name prefix
     * @param label tag-input prefix, ending in NUL
     * @param hkdfInfo HKDF-SHA256 info for the frame key
     * @param maxFrameBytes largest frame accepted or sealed, at most {@link #MAX_FRAME_BYTES}; a longer length prefix
     *        is refused before any body byte is buffered
     * @param writeStallMillis direct transports only, 0 to disable: a socket write (inline or by the writer thread)
     *        that makes no progress (64 KiB) for this long closes that connection ({@link #writeStalls}), so a peer
     *        that stops reading cannot hold a sender forever; socket writes have no timeout of their own
     */
    public record Domain(String name, String label, String hkdfInfo, int maxFrameBytes, long writeStallMillis) {
        public static final Domain RAFT = new Domain("raft", "sjws-raft-frame\0", "sjws-raft-frame-v1", MAX_FRAME_BYTES);

        /** A domain without a write-stall watchdog. */
        public Domain(String name, String label, String hkdfInfo, int maxFrameBytes) {
            this(name, label, hkdfInfo, maxFrameBytes, 0);
        }

        /** This domain with {@code writeStallMillis} set (see the record component). */
        public Domain withWriteStallMillis(long millis) {
            return new Domain(name, label, hkdfInfo, maxFrameBytes, millis);
        }

        public Domain {
            Objects.requireNonNull(name, "name");
            if (writeStallMillis < 0) {
                throw new IllegalArgumentException("writeStallMillis");
            }
            if (maxFrameBytes < HEADER_BYTES + MAC_LENGTH + 1 || maxFrameBytes > MAX_FRAME_BYTES) {
                throw new IllegalArgumentException("maxFrameBytes");
            }
            if (label == null || !label.endsWith("\0") || hkdfInfo == null || hkdfInfo.isEmpty()) {
                throw new IllegalArgumentException("domain label must end in NUL and hkdfInfo must be set");
            }
        }
    }

    /** {@link #seal} refuses a body or group too large for the frame format. */
    static final class FrameTooLargeException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        FrameTooLargeException() {
            super("frame too large");
        }
    }

    /** The single place sockets are created. Security replaces it for mTLS. */
    public interface SocketFactory {
        ServerSocket listen(InetSocketAddress bind) throws IOException;

        Socket connect(InetSocketAddress to) throws IOException;

        /**
         * Completes an accepted socket's authentication (the mTLS handshake) before it may take an inbound slot.
         * Must finish or throw within {@code totalMillis} in total, not per read. Runs off the acceptor thread.
         * Plain sockets have nothing to do.
         */
        default void handshake(Socket accepted, int totalMillis) throws IOException {
        }

        /**
         * The wire id that {@code accepted}'s authenticated identity (the verified mTLS certificate, mapped through a
         * pinned peer list) belongs to, called once after {@link #handshake}; -1 when this factory does not identify
         * peers (plain sockets, the CP voter links). With a pinned wire, a frame on that connection whose sender wire
         * differs is refused as {@link Reason#ORIGIN}, the connection is closed, and that identity's next
         * connections are held off with a doubling backoff ({@link #originHeldOff}): the header wire id is
         * authenticated by the shared key only, so it is never trusted as identity. Throwing refuses the connection.
         */
        default int peerWire(Socket accepted) {
            return -1;
        }

        /**
         * A socket whose {@code close()} never waits behind a blocked write on {@code connected}, used to abort a
         * stalled connection ({@link Domain#writeStallMillis}) and on {@link TcpTransport#close}. A plain socket is
         * its own handle. An {@code SSLSocket}'s {@code close()} sends close_notify under the TLS output lock that a
         * blocked writer holds for its whole write, so a TLS factory layers TLS over a plain socket and returns that
         * plain socket here; closing it fails the blocked write at once.
         */
        default Socket abortHandle(Socket connected) {
            return connected;
        }

        SocketFactory PLAIN = new SocketFactory() {
            @Override
            public ServerSocket listen(InetSocketAddress bind) throws IOException {
                var server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(bind);
                return server;
            }

            @Override
            public Socket connect(InetSocketAddress to) throws IOException {
                var socket = new Socket();
                socket.setTcpNoDelay(true);
                socket.connect(to, CONNECT_TIMEOUT_MILLIS);
                return socket;
            }
        };
    }

    private final NodeId self;
    private final Map<Integer, NodeId> byWire = new HashMap<>();
    private final Map<NodeId, Peer> peers = new LinkedHashMap<>();
    private final Set<Socket> inboundSockets = ConcurrentHashMap.newKeySet();
    private final Set<NodeId> cut = ConcurrentHashMap.newKeySet();
    private final ArrayBlockingQueue<Inbound> inbound;
    private final SocketFactory sockets;
    private final Clock clock;
    private final byte[] currentKey;
    private final byte[] previousKey;
    private final long bootEpoch;
    private final AtomicLong sendSeq = new AtomicLong();
    private final AtomicLong sendDrops = new AtomicLong();
    private final AtomicLong inboundDrops = new AtomicLong();
    private final AtomicLong inboundRefused = new AtomicLong();
    private final AtomicLong strangersRefused = new AtomicLong();
    private final AtomicLong handshakeFailures = new AtomicLong();
    private final AtomicLong handshakesRefused = new AtomicLong();
    private final AtomicInteger handshaking = new AtomicInteger();
    private final int maxHandshakes;
    private final Set<java.net.InetAddress> memberHosts = new java.util.HashSet<>();
    private final AtomicLong oversizeDrops = new AtomicLong();
    private final AtomicLong peakReadBuffer = new AtomicLong();
    private final AtomicInteger inboundCount = new AtomicInteger();
    private final int readTimeoutMillis;
    private final int maxInbound;
    private final AtomicInteger[] rejected = new AtomicInteger[Reason.values().length];
    private final ReentrantLock replayLock = new ReentrantLock();
    private final AuthReplay replay = new AuthReplay();
    private final ServerSocket server;
    private final Thread acceptor;
    /** Direct transports with a write-stall bound only; else null. */
    private final Thread stallWatch;
    private final AtomicLong writeStalls = new AtomicLong();
    private final AtomicLong writerErrors = new AtomicLong();
    private final AtomicLong inlineWrites = new AtomicLong();
    private volatile BiConsumer<NodeId, SimTransport.Frame> handler;
    private volatile boolean open = true;
    private final AtomicLong originMismatches = new AtomicLong();
    private final AtomicLong originHeldOff = new AtomicLong();
    /** Pinned wire → {mismatches, held off until (nanoTime)}. Touched only on a mismatch or by a pinned accept. */
    private final ConcurrentHashMap<Integer, long[]> originBackoff = new ConcurrentHashMap<>();
    /** First hold-off after an origin mismatch; it doubles per further mismatch up to {@link #ORIGIN_BACKOFF_MAX_MILLIS}. */
    public static final long ORIGIN_BACKOFF_MILLIS = 1_000;
    public static final long ORIGIN_BACKOFF_MAX_MILLIS = 60_000;
    private static final System.Logger LOG = System.getLogger(TcpTransport.class.getName());

    /**
     * Listens on {@code self}'s member address and dials the others.
     *
     * @param queueCapacity frames buffered per peer and inbound before a drop is counted
     */
    public static TcpTransport open(NodeId self, List<Member> members, ClusterKey key, Clock clock,
            int queueCapacity) throws IOException {
        return open(self, members, key, clock, queueCapacity, SocketFactory.PLAIN);
    }

    public static TcpTransport open(NodeId self, List<Member> members, ClusterKey key, Clock clock,
            int queueCapacity, SocketFactory sockets) throws IOException {
        return new TcpTransport(self, members, key, clock, queueCapacity, sockets, READ_TIMEOUT_MILLIS, Domain.RAFT,
                false);
    }

    /**
     * A transport for another protocol than Raft, with every limit above unchanged.
     *
     * @param domain tag label and key derivation; frames of other domains fail the tag check
     * @param direct true: each authenticated frame is handed to the attached handler on its connection's reader
     *        thread as it arrives, in order, and {@link #deliver} does nothing (the handler must not block: it holds
     *        up that peer's later frames); false: frames wait for {@link #deliver}, as for Raft
     */
    public static TcpTransport open(NodeId self, List<Member> members, ClusterKey key, Clock clock,
            int queueCapacity, SocketFactory sockets, Domain domain, boolean direct) throws IOException {
        return new TcpTransport(self, members, key, clock, queueCapacity, sockets, READ_TIMEOUT_MILLIS, domain,
                direct);
    }

    /** Test seam for a shorter read deadline. */
    static TcpTransport open(NodeId self, List<Member> members, ClusterKey key, Clock clock, int queueCapacity,
            int readTimeoutMillis) throws IOException {
        return new TcpTransport(self, members, key, clock, queueCapacity, SocketFactory.PLAIN, readTimeoutMillis,
                Domain.RAFT, false);
    }

    private TcpTransport(NodeId self, List<Member> members, ClusterKey key, Clock clock, int queueCapacity,
            SocketFactory sockets, int readTimeoutMillis, Domain domain, boolean direct) throws IOException {
        this.self = Objects.requireNonNull(self, "self");
        this.domain = Objects.requireNonNull(domain, "domain");
        this.direct = direct;
        this.maxFrameBytes = domain.maxFrameBytes();
        this.label = domain.label().getBytes(StandardCharsets.US_ASCII);
        this.hkdfInfo = domain.hkdfInfo().getBytes(StandardCharsets.US_ASCII);
        Objects.requireNonNull(key, "key");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sockets = Objects.requireNonNull(sockets, "sockets");
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity");
        }
        if (readTimeoutMillis < 1) {
            throw new IllegalArgumentException("readTimeoutMillis");
        }
        this.readTimeoutMillis = readTimeoutMillis;
        for (int i = 0; i < rejected.length; i++) {
            rejected[i] = new AtomicInteger();
        }
        this.inbound = new ArrayBlockingQueue<>(queueCapacity);
        this.currentKey = derive(key.current());
        this.previousKey = key.hasPrevious() ? derive(key.previous()) : null;
        this.bootEpoch = clock.currentTimeMillis();
        InetSocketAddress bind = null;
        for (var member : members) {
            if (byWire.put(member.id().wire(), member.id()) != null) {
                throw new IllegalArgumentException("duplicate wire");
            }
            var host = member.address().getAddress();
            if (host == null) {
                try {
                    host = java.net.InetAddress.getByName(member.address().getHostString());
                } catch (java.net.UnknownHostException unresolved) {
                    host = null; // its connections are refused until mTLS; DNS is not consulted per accept
                }
            }
            if (host != null) {
                memberHosts.add(host);
            }
            if (member.id().equals(self)) {
                bind = member.address();
            } else {
                peers.put(member.id(), new Peer(member.address(), queueCapacity));
            }
        }
        if (bind == null) {
            throw new IllegalArgumentException("self is not a member");
        }
        this.maxInbound = Math.max(2, 2 * peers.size());
        this.maxHandshakes = Math.max(8, HANDSHAKES_PER_PEER * peers.size());
        this.server = sockets.listen(bind);
        this.acceptor = Thread.ofVirtual().name(domain.name() + "-accept-" + self.name()).start(this::accept);
        this.stallWatch = direct && domain.writeStallMillis() > 0
                ? Thread.ofVirtual().name(domain.name() + "-stallwatch-" + self.name()).start(this::watchStalls)
                : null;
    }

    /** Closes connections whose current socket write has been blocked longer than the domain's bound. */
    private void watchStalls() {
        long bound = domain.writeStallMillis() * 1_000_000L;
        long period = Math.max(1, Math.min(50, domain.writeStallMillis() / 4));
        while (open) {
            try {
                Thread.sleep(period);
            } catch (InterruptedException closing) {
                return;
            }
            long now = System.nanoTime();
            for (var peer : peers.values()) {
                var c = peer.conn; // one snapshot: the stamp and the socket belong to the same connection
                if (c == null) {
                    continue;
                }
                long since = c.writingSince;
                if (since != 0 && now - since > bound && c.aborted.compareAndSet(false, true)) {
                    writeStalls.incrementAndGet();
                    peer.logStall(now);
                    // Never close on this thread: whatever a close might wait for, the next pass still runs.
                    Thread.ofVirtual().name(domain.name() + "-stall-abort-" + self.name()).start(c::abort);
                }
            }
        }
    }

    /** Connections closed because a socket write stayed blocked past {@link Domain#writeStallMillis}. */
    public long writeStalls() {
        return writeStalls.get();
    }

    /** Frames {@link #sendNow} wrote on the caller's thread. */
    public long inlineWrites() {
        return inlineWrites.get();
    }

    /** Unexpected (unchecked) failures caught by a direct writer, which then backs off and keeps running. */
    public long writerErrors() {
        return writerErrors.get();
    }

    @Override
    public ClusterRpc attach(NodeId node, BiConsumer<NodeId, SimTransport.Frame> handler) {
        if (!self.equals(node)) {
            throw new IllegalArgumentException("not this transport's node " + node);
        }
        if (this.handler != null) {
            throw new IllegalArgumentException("duplicate " + node);
        }
        this.handler = Objects.requireNonNull(handler, "handler");
        return (to, frame) -> {
            var peer = peers.get(to);
            if (peer == null || cut.contains(to) || !open || !peer.enqueue(frame)) {
                sendDrops.incrementAndGet();
            }
        };
    }

    /**
     * Direct transports only (#236): sends {@code frame} like the attached {@link ClusterRpc}, but writes it on the
     * calling thread when that connection is up and idle, saving the hand-off to its writer thread. The caller may
     * then block while the socket's send buffer is full (back-pressure from that peer), so only callers that may
     * block on that peer use it; everything else goes through the writer thread as before. The block is bounded by
     * {@link Domain#writeStallMillis} when set. Returns false when the frame was dropped (counted in
     * {@link #sendDrops}): the peer is unknown, cut or closed, its queue is full, or the inline write failed.
     */
    public boolean sendNow(NodeId to, SimTransport.Frame frame) {
        Objects.requireNonNull(frame, "frame");
        if (!direct) {
            throw new IllegalStateException("sendNow needs a direct transport");
        }
        var peer = peers.get(to);
        if (peer == null || cut.contains(to) || !open) {
            sendDrops.incrementAndGet();
            return false;
        }
        int inline = peer.tryInline(frame);
        if (inline == Peer.WRITTEN) {
            inlineWrites.incrementAndGet();
            return true;
        }
        if (inline == Peer.FAILED) {
            return false; // dropped and counted: the connection failed under this write
        }
        if (!peer.enqueue(frame)) {
            sendDrops.incrementAndGet();
            return false;
        }
        return true;
    }

    @Override
    public int deliver(int max) {
        if (direct) {
            return 0;
        }
        var target = handler;
        int ran = 0;
        while (ran < max) {
            var next = inbound.poll();
            if (next == null) {
                break;
            }
            if (target != null && !cut.contains(next.from)) {
                target.accept(next.from, next.frame);
            }
            ran++;
        }
        return ran;
    }

    /** Port actually bound, for tests that listen on an ephemeral port. */
    public int localPort() {
        return server.getLocalPort();
    }

    /** Frames the Raft thread could not queue, or a writer could not put on a socket. */
    public long sendDrops() {
        return sendDrops.get();
    }

    /** Authenticated frames dropped because the inbound queue was full. */
    public long inboundDrops() {
        return inboundDrops.get();
    }

    /** Inbound connections closed at accept because {@code 2 x peers} were already open. */
    public long inboundRefused() {
        return inboundRefused.get();
    }

    /**
     * Inbound connections closed at accept because the source address is no member's address (only while the
     * {@link SocketFactory} does not authenticate peers).
     */
    public long strangersRefused() {
        return strangersRefused.get();
    }

    /** Accepted sockets closed at once because {@code 8 x peers} handshakes were already in flight. */
    public long handshakesRefused() {
        return handshakesRefused.get();
    }

    /** Concurrent in-flight inbound handshakes allowed per peer; each one costs a thread and a TLS engine. */
    public static final int HANDSHAKES_PER_PEER = 8;

    /** Inbound handshakes currently in flight. */
    public int handshaking() {
        return handshaking.get();
    }

    /** Accepted sockets closed because {@link SocketFactory#handshake} failed or missed its deadline. */
    public long handshakeFailures() {
        return handshakeFailures.get();
    }

    /** Outbound frames dropped because they could not be sealed (body or group too large). */
    public long oversizeDrops() {
        return oversizeDrops.get();
    }

    /** Largest pre-auth read buffer any reader has allocated, for the allocation test. */
    long peakReadBuffer() {
        return peakReadBuffer.get();
    }

    /** Inbound connections currently open. */
    public int inboundConnections() {
        return inboundCount.get();
    }

    public int rejected(Reason reason) {
        return rejected[Objects.requireNonNull(reason, "reason").ordinal()].get();
    }

    /** Test partition: drop everything to and from {@code peer} until {@link #heal}. */
    void cut(NodeId peer) {
        cut.add(peer);
    }

    void heal(NodeId peer) {
        cut.remove(peer);
    }

    @Override
    public void close() {
        open = false;
        closeQuietly(server);
        acceptor.interrupt();
        if (stallWatch != null) {
            stallWatch.interrupt();
        }
        for (var peer : peers.values()) {
            peer.close();
        }
        for (var socket : inboundSockets) {
            closeQuietly(socket);
        }
        inbound.clear();
    }

    private void accept() {
        while (open) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException failed) {
                if (!open) {
                    return;
                }
                continue;
            }
            if (!memberHosts.contains(socket.getInetAddress())) {
                strangersRefused.incrementAndGet();
                closeQuietly(socket);
                continue;
            }
            if (handshaking.incrementAndGet() > maxHandshakes) {
                handshaking.decrementAndGet();
                handshakesRefused.incrementAndGet();
                closeQuietly(socket);
                continue;
            }
            inboundSockets.add(socket); // closed by close() even while its handshake runs
            if (!open) {
                handshaking.decrementAndGet();
                closeQuietly(socket);
                inboundSockets.remove(socket);
                return;
            }
            thread("-admit-").start(() -> admit(socket));
        }
    }

    /** Handshake first (no slot held), then take a slot or be refused, then read. */
    private void admit(Socket socket) {
        try {
            sockets.handshake(socket, HANDSHAKE_TIMEOUT_MILLIS);
        } catch (IOException | RuntimeException failed) {
            handshakeFailures.incrementAndGet();
            closeQuietly(socket);
            inboundSockets.remove(socket);
            return;
        } finally {
            handshaking.decrementAndGet();
        }
        int pinned;
        try {
            pinned = sockets.peerWire(socket);
        } catch (RuntimeException unidentified) {
            handshakeFailures.incrementAndGet();
            closeQuietly(socket);
            inboundSockets.remove(socket);
            return;
        }
        if (pinned >= 0 && heldOff(pinned)) {
            originHeldOff.incrementAndGet();
            closeQuietly(socket);
            inboundSockets.remove(socket);
            return;
        }
        if (inboundCount.incrementAndGet() > maxInbound) {
            inboundCount.decrementAndGet();
            inboundRefused.incrementAndGet();
            closeQuietly(socket);
            inboundSockets.remove(socket);
            return;
        }
        try {
            socket.setSoTimeout(readTimeoutMillis);
        } catch (IOException failed) {
            closeQuietly(socket);
            inboundSockets.remove(socket);
            inboundCount.decrementAndGet();
            return;
        }
        read(socket, pinned);
    }

    /**
     * Connection threads. Raft keeps virtual threads. A {@code direct} transport answers requests on its reader
     * threads, so its readers and writers are platform daemons: a virtual thread blocked on a socket is woken
     * through the JDK's read poller, an extra hand-off per frame that the per-hop budget (#236 G3) cannot afford.
     * Their number stays bounded by the member list and the handshake cap.
     */
    private Thread.Builder thread(String role) {
        var name = domain.name() + role + self.name();
        return direct ? Thread.ofPlatform().daemon().name(name) : Thread.ofVirtual().name(name);
    }

    private boolean heldOff(int pinned) {
        var b = originBackoff.get(pinned);
        return b != null && System.nanoTime() - b[1] < 0;
    }

    /** An authenticated identity sent a frame as another wire: close, hold its reconnects off, and alert. */
    private void originMismatch(int pinned, int claimed) {
        originMismatches.incrementAndGet();
        var b = originBackoff.compute(pinned, (k, old) -> {
            long n = old == null ? 1 : old[0] + 1;
            long ms = Math.min(ORIGIN_BACKOFF_MAX_MILLIS, ORIGIN_BACKOFF_MILLIS << Math.min(n - 1, 16));
            return new long[] {n, System.nanoTime() + ms * 1_000_000L};
        });
        LOG.log(System.Logger.Level.WARNING, "{0} transport on {1}: peer wire {2} sent a frame as wire {3} "
                + "(misconfiguration or a compromised peer); closed, mismatch {4}, its connections held off",
                domain.name(), self.name(), pinned, claimed, b[0]);
    }

    /** Correctly tagged frames refused because their sender wire was not the connection's pinned identity. */
    public long originMismatches() {
        return originMismatches.get();
    }

    /** Authenticated connections closed at once because their identity is held off after an origin mismatch. */
    public long originHeldOff() {
        return originHeldOff.get();
    }

    /** Thrown inside {@link #open} on {@link Reason#ORIGIN}: the reader closes the connection. */
    private static final class OriginMismatch extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int claimed;

        OriginMismatch(int claimed) {
            super(null, null, false, false);
            this.claimed = claimed;
        }
    }

    private void read(Socket socket, int pinned) {
        try (socket; var in = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            var current = mac(currentKey);
            var previous = previousKey == null ? null : mac(previousKey);
            var tag = new byte[MAC_LENGTH];
            var prefix = new byte[4];
            while (open) {
                socket.setSoTimeout(readTimeoutMillis);
                int first = in.read(); // idle wait between frames: the plain read timeout
                if (first < 0) {
                    return;
                }
                // The whole-frame deadline starts at the first prefix byte, so a trickled prefix counts too.
                long deadline = System.nanoTime() + readTimeoutMillis * 1_000_000L;
                prefix[0] = (byte) first;
                if (!readFully(socket, in, prefix, 1, 3, deadline)) {
                    return;
                }
                int length = ByteBuffer.wrap(prefix).getInt();
                if (length < HEADER_BYTES + MAC_LENGTH || length > maxFrameBytes) {
                    drop(Reason.MALFORMED);
                    return;
                }
                var bytes = readFrame(socket, in, length, deadline);
                if (bytes == null) {
                    return; // whole-frame deadline passed: slowloris
                }
                Inbound accepted;
                try {
                    accepted = open(bytes, current, previous, clock.currentTimeMillis(), tag, pinned);
                } catch (OriginMismatch spoofed) {
                    originMismatch(pinned, spoofed.claimed);
                    return;
                }
                if (accepted == null || cut.contains(accepted.from)) {
                    continue;
                }
                if (direct) {
                    var target = handler;
                    if (target != null) {
                        target.accept(accepted.from, accepted.frame);
                    }
                } else if (!inbound.offer(accepted)) {
                    inboundDrops.incrementAndGet();
                }
            }
        } catch (IOException closed) {
            // Peer went away or we are closing; the peer's writer reconnects.
        } finally {
            inboundSockets.remove(socket);
            inboundCount.decrementAndGet();
        }
    }

    /**
     * Reads {@code length} bytes into a buffer that starts at {@link #PREAUTH_CHUNK_BYTES} and doubles only once
     * full, so memory stays within 2x of what the sender actually sent. Returns null once {@code deadline}
     * (set at the first prefix byte) has passed.
     */
    private byte[] readFrame(Socket socket, DataInputStream in, int length, long deadline) throws IOException {
        var bytes = new byte[Math.min(length, PREAUTH_CHUNK_BYTES)];
        notePeak(bytes.length);
        int filled = 0;
        while (filled < length) {
            if (filled == bytes.length) {
                bytes = java.util.Arrays.copyOf(bytes, (int) Math.min(length, 2L * bytes.length));
                notePeak(bytes.length);
            }
            int want = bytes.length - filled;
            if (!readFully(socket, in, bytes, filled, want, deadline)) {
                return null;
            }
            filled += want;
        }
        return bytes;
    }

    /** Fills {@code len} bytes before {@code deadline}; false (counted as TIMEOUT) once it passes. */
    private boolean readFully(Socket socket, DataInputStream in, byte[] into, int off, int len, long deadline)
            throws IOException {
        int done = 0;
        while (done < len) {
            long left = (deadline - System.nanoTime()) / 1_000_000L;
            if (left <= 0) {
                drop(Reason.TIMEOUT);
                return false;
            }
            socket.setSoTimeout((int) Math.min(left, readTimeoutMillis));
            int n;
            try {
                n = in.read(into, off + done, len - done);
            } catch (java.net.SocketTimeoutException stalled) {
                drop(Reason.TIMEOUT);
                return false;
            }
            if (n < 0) {
                throw new java.io.EOFException();
            }
            done += n;
        }
        return true;
    }

    private void notePeak(long size) {
        peakReadBuffer.accumulateAndGet(size, Math::max);
    }

    /** Checks one frame. Returns it, or null after counting why it was dropped. */
    Inbound open(byte[] bytes, Mac current, Mac previous, long nowMillis) {
        return open(bytes, current, previous, nowMillis, new byte[MAC_LENGTH], -1);
    }

    private Inbound open(byte[] bytes, Mac current, Mac previous, long nowMillis, byte[] tag, int pinned) {
        var buf = ByteBuffer.wrap(bytes);
        int version = buf.get();
        int keyId = buf.get();
        if (version != VERSION || !AuthReplay.keyIdOk(keyId)) {
            return drop(Reason.MALFORMED);
        }
        var mac = keyId == AuthReplay.KEY_CURRENT ? current : previous;
        if (mac == null || !tagMatches(mac, bytes, tag)) {
            return drop(Reason.TAG);
        }
        int from = buf.getShort() & 0xFFFF;
        if (pinned >= 0 && from != pinned) {
            drop(Reason.ORIGIN);
            throw new OriginMismatch(from);
        }
        int dest = buf.getShort() & 0xFFFF;
        long epoch = buf.getLong();
        long seq = buf.getLong();
        int type = buf.getInt();
        int groupLength = buf.getShort() & 0xFFFF;
        int bodyLength = bytes.length - MAC_LENGTH - HEADER_BYTES - groupLength;
        if (groupLength == 0 || bodyLength < 0) {
            return drop(Reason.MALFORMED);
        }
        var sender = byWire.get(from);
        AuthReplay.Decision decision;
        replayLock.lock();
        try {
            decision = replay.accept(from, dest, self.wire(), epoch, seq, nowMillis, EPOCH_SILENCE_MILLIS,
                    wire -> {
                        var id = byWire.get(wire);
                        return id != null && !id.equals(self);
                    });
        } finally {
            replayLock.unlock();
        }
        if (decision != AuthReplay.Decision.OK) {
            return drop(switch (decision) {
                case DEST -> Reason.DEST;
                case MEMBER -> Reason.MEMBER;
                case EPOCH -> Reason.EPOCH;
                case SEQ -> Reason.SEQ;
                case OK -> throw new IllegalStateException();
            });
        }
        var group = new String(bytes, HEADER_BYTES, groupLength, StandardCharsets.UTF_8);
        var body = new byte[bodyLength];
        System.arraycopy(bytes, HEADER_BYTES + groupLength, body, 0, bodyLength);
        return new Inbound(sender, new SimTransport.Frame(new GroupId(group), type, body));
    }

    /** Builds one sealed frame, without the length prefix. */
    byte[] seal(Mac mac, int keyId, NodeId to, long epoch, long seq, SimTransport.Frame frame) {
        var group = frame.group().value().getBytes(StandardCharsets.UTF_8);
        var body = frame.body();
        int length = HEADER_BYTES + group.length + body.length + MAC_LENGTH;
        if (group.length > 0xFFFF || length > maxFrameBytes) {
            throw new FrameTooLargeException();
        }
        var bytes = new byte[length];
        ByteBuffer.wrap(bytes).put((byte) VERSION).put((byte) keyId).putShort((short) self.wire())
                .putShort((short) to.wire()).putLong(epoch).putLong(seq).putInt(frame.type())
                .putShort((short) group.length).put(group).put(body);
        mac.reset();
        mac.update(label);
        mac.update(bytes, 0, length - MAC_LENGTH);
        try {
            mac.doFinal(bytes, length - MAC_LENGTH);
        } catch (GeneralSecurityException failed) {
            throw new IllegalStateException(failed);
        }
        return bytes;
    }

    Mac currentMac() {
        return mac(currentKey);
    }

    /** Constant-time tag check into a reused per-reader buffer; no allocation per frame. */
    private boolean tagMatches(Mac mac, byte[] bytes, byte[] tag) {
        mac.reset();
        mac.update(label);
        int at = bytes.length - MAC_LENGTH;
        mac.update(bytes, 0, at);
        try {
            mac.doFinal(tag, 0);
        } catch (GeneralSecurityException failed) {
            throw new IllegalStateException(failed);
        }
        int diff = 0;
        for (int i = 0; i < MAC_LENGTH; i++) {
            diff |= tag[i] ^ bytes[at + i];
        }
        return diff == 0;
    }

    private Inbound drop(Reason reason) {
        rejected[reason.ordinal()].incrementAndGet();
        return null;
    }

    private static Mac mac(byte[] key) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac;
        } catch (GeneralSecurityException failed) {
            throw new IllegalStateException(failed);
        }
    }

    private byte[] derive(byte[] ikm) {
        try {
            var kdf = KDF.getInstance("HKDF-SHA256");
            return kdf.deriveData(HKDFParameterSpec.ofExtract().addIKM(ikm).thenExpand(hkdfInfo, MAC_LENGTH));
        } catch (GeneralSecurityException failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Closing; nothing to report.
        }
    }

    record Inbound(NodeId from, SimTransport.Frame frame) {
    }

    /** One peer: a bounded queue and a writer that owns the socket. */
    private final class Peer {
        private final InetSocketAddress address;
        private final ArrayBlockingQueue<SimTransport.Frame> queue;
        private final Thread writer;
        private volatile Socket socket;

        private Peer(InetSocketAddress address, int capacity) {
            this.address = address;
            this.queue = new ArrayBlockingQueue<>(capacity);
            this.writer = thread("-write-").start(direct ? this::writeDirect : this::write);
        }

        // ---- direct transports only (#236): the caller may write inline when nothing is queued ----
        static final int WRITTEN = 0, NOT_IDLE = 1, FAILED = 2;
        /** Bytes between write-progress stamps for the stall watchdog. */
        static final int PROGRESS_CHUNK = 64 << 10;
        /** Guards {@link #out} and the frame MAC on a direct transport; sealing under it keeps seq order = wire order. */
        private final ReentrantLock io = new ReentrantLock();
        /** Frames offered to {@link #queue} and not yet written (or dropped) by the writer. */
        private final AtomicInteger backlog = new AtomicInteger();
        /** The current outbound connection on a direct transport, null when none (read by the stall watchdog). */
        private volatile Conn conn;
        /** Stall warnings: at most one per {@link #STALL_LOG_NANOS} per peer, with the count suppressed meanwhile. */
        private long lastStallLog;
        private long stallsUnlogged;
        private static final long STALL_LOG_NANOS = 60_000_000_000L;

        /** Watchdog thread only. */
        private void logStall(long now) {
            if (lastStallLog != 0 && now - lastStallLog < STALL_LOG_NANOS) {
                stallsUnlogged++;
                return;
            }
            LOG.log(System.Logger.Level.WARNING, "{0} transport on {1}: a write to {2} was blocked over {3} ms; "
                    + "connection closed ({4} more since the last warning)", domain.name(), self.name(), address,
                    domain.writeStallMillis(), stallsUnlogged);
            lastStallLog = now;
            stallsUnlogged = 0;
        }
        private Mac directMac;
        private DataOutputStream out;
        private boolean failing;

        private boolean enqueue(SimTransport.Frame frame) {
            Objects.requireNonNull(frame, "frame");
            if (!direct) {
                return queue.offer(frame);
            }
            backlog.incrementAndGet();
            if (!queue.offer(frame)) {
                backlog.decrementAndGet();
                return false;
            }
            return true;
        }

        /**
         * Writes {@code frame} on the calling thread when the connection is up and no queued frame is ahead of it.
         * {@link #NOT_IDLE}: nothing was written, queue it; {@link #FAILED}: it was dropped with the connection.
         */
        private void writing(long since) {
            var c = conn;
            if (c != null) {
                c.writingSince = since;
            }
        }

        private void dropConn() {
            var c = conn;
            conn = null;
            if (c != null) {
                c.abort();
            } else {
                closeQuietly(socket);
            }
            socket = null;
            out = null;
        }

        private int tryInline(SimTransport.Frame frame) {
            if (backlog.get() != 0 || !io.tryLock()) {
                return NOT_IDLE;
            }
            try {
                if (backlog.get() != 0 || out == null || !open) {
                    return NOT_IDLE;
                }
                return writeLocked(frame, true) ? WRITTEN : FAILED;
            } finally {
                io.unlock();
            }
        }

        /** {@link #io} held. Returns false when the frame was dropped with the connection (counted). */
        private boolean writeLocked(SimTransport.Frame frame, boolean flush) {
            byte[] bytes;
            try {
                if (directMac == null) {
                    directMac = mac(currentKey);
                }
                bytes = seal(directMac, HeartbeatAuthenticator.KEY_CURRENT, peerId(), bootEpoch,
                        sendSeq.incrementAndGet(), frame);
            } catch (FrameTooLargeException unsealable) {
                oversizeDrops.incrementAndGet();
                sendDrops.incrementAndGet();
                // N1: frames already buffered must not wait for a later send
                return !flush || out == null || flushLocked();
            } catch (RuntimeException unsealable) {
                // N3: a failure building the MAC or sealing drops this frame only; the writer and connection live on
                writerErrors.incrementAndGet();
                sendDrops.incrementAndGet();
                directMac = null;
                return false;
            }
            try {
                if (out == null) {
                    if (!open) {
                        sendDrops.incrementAndGet();
                        return false;
                    }
                    var fresh = sockets.connect(address);
                    var c = new Conn(fresh, sockets.abortHandle(fresh));
                    socket = fresh;
                    conn = c;
                    if (!open) {
                        // N2: close() ran while this connect was in progress; never keep a socket past close
                        dropConn();
                        sendDrops.incrementAndGet();
                        return false;
                    }
                    out = new DataOutputStream(new BufferedOutputStream(fresh.getOutputStream()));
                }
                writing(System.nanoTime());
                out.writeInt(bytes.length);
                // The stall bound times lack of progress, not the frame: the stamp moves on with every chunk, so a
                // large frame on a slow but moving link is not cut.
                for (int off = 0; off < bytes.length; off += PROGRESS_CHUNK) {
                    if (off > 0) {
                        writing(System.nanoTime());
                    }
                    out.write(bytes, off, Math.min(PROGRESS_CHUNK, bytes.length - off));
                }
                if (flush) {
                    out.flush();
                }
                writing(0);
                failing = false;
                return true;
            } catch (IOException | RuntimeException failed) {
                writing(0);
                sendDrops.incrementAndGet();
                if (failed instanceof RuntimeException) {
                    writerErrors.incrementAndGet();
                }
                if (!failing && open) {
                    failing = true;
                    LOG.log(System.Logger.Level.WARNING, "{0} transport on {1}: sending to {2} failed ({3}); "
                            + "reconnecting", domain.name(), self.name(), address, failed.getClass().getSimpleName());
                }
                dropConn();
                return false;
            }
        }

        /** {@link #io} held. Flushes buffered frames; false drops the connection. */
        private boolean flushLocked() {
            try {
                writing(System.nanoTime());
                out.flush();
                writing(0);
                return true;
            } catch (IOException failed) {
                writing(0);
                dropConn();
                return false;
            }
        }

        private void writeDirect() {
            while (open) {
                SimTransport.Frame frame;
                try {
                    frame = queue.take();
                } catch (InterruptedException interrupted) {
                    return;
                }
                boolean ok;
                io.lock();
                try {
                    // N3: writeLocked catches unchecked failures too, so this thread never dies on one
                    ok = writeLocked(frame, queue.isEmpty());
                } finally {
                    io.unlock();
                    backlog.decrementAndGet();
                }
                if (!ok && open) {
                    try {
                        Thread.sleep(RECONNECT_MILLIS);
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                }
            }
        }

        private void write() {
            var mac = mac(currentKey);
            NodeId to = null;
            DataOutputStream out = null;
            while (open) {
                SimTransport.Frame frame;
                try {
                    frame = queue.take();
                } catch (InterruptedException interrupted) {
                    return;
                }
                if (to == null) {
                    to = peerId();
                }
                byte[] bytes;
                try {
                    bytes = seal(mac, HeartbeatAuthenticator.KEY_CURRENT, to, bootEpoch,
                            sendSeq.incrementAndGet(), frame);
                } catch (FrameTooLargeException unsealable) {
                    // Oversize body or group: drop this frame only and keep the writer alive.
                    oversizeDrops.incrementAndGet();
                    sendDrops.incrementAndGet();
                    if (out != null && queue.isEmpty()) {
                        try {
                            out.flush();
                        } catch (IOException failed) {
                            closeQuietly(socket);
                            socket = null;
                            out = null;
                        }
                    }
                    continue;
                }
                try {
                    if (out == null) {
                        socket = sockets.connect(address);
                        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                    }
                    out.writeInt(bytes.length);
                    out.write(bytes);
                    if (queue.isEmpty()) {
                        out.flush();
                    }
                } catch (IOException failed) {
                    sendDrops.incrementAndGet();
                    closeQuietly(socket);
                    socket = null;
                    out = null;
                    try {
                        Thread.sleep(RECONNECT_MILLIS);
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                }
            }
        }

        private NodeId peerId() {
            for (var entry : peers.entrySet()) {
                if (entry.getValue() == this) {
                    return entry.getKey();
                }
            }
            throw new IllegalStateException("peer");
        }

        private void close() {
            writer.interrupt();
            var c = conn;
            if (c != null) {
                c.abort(); // the abort handle first: a plain close never waits behind a blocked (TLS) write
            }
            var current = socket;
            if (current != null) {
                closeQuietly(current);
            }
        }
    }

    /** One outbound connection of a direct transport: its socket, abort handle and write-in-progress stamp. */
    private static final class Conn {
        final Socket socket;
        final Socket abort;
        /** nanoTime when the current socket write started, 0 when none is in progress. */
        volatile long writingSince;
        final java.util.concurrent.atomic.AtomicBoolean aborted = new java.util.concurrent.atomic.AtomicBoolean();

        Conn(Socket socket, Socket abort) {
            this.socket = socket;
            this.abort = abort;
        }

        /** Closes the abort handle with linger 0 (an immediate reset, no flush), then the socket. */
        void abort() {
            try {
                abort.setSoLinger(true, 0);
            } catch (IOException | RuntimeException ignored) {
                // already closed
            }
            closeQuietly(abort);
            if (socket != abort) {
                closeQuietly(socket);
            }
        }
    }
}
