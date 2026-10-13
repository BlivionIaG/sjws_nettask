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
 * HKDF-SHA256 of the {@link ClusterKey} with info {@code sjws-raft-frame-v1}, so it is not the
 * heartbeat key. A frame for another destination, from a non-member, with a bad tag, an older
 * epoch or a sequence already seen is dropped and counted by {@link Reason}.
 *
 * <p>The Raft thread only calls {@link ClusterRpc#send}, which offers into a bounded per-peer
 * queue and counts a drop when it is full. Writer and reader threads are virtual. Inbound
 * frames wait in a bounded queue until {@link #deliver} runs them on the Raft thread. Every
 * socket comes from one {@link SocketFactory}, which is where mTLS plugs in.
 */
public final class TcpTransport implements ClusterTransport, AutoCloseable {

    public static final int VERSION = 1;
    public static final int MAX_FRAME_BYTES = 16 << 20;
    public static final int MAC_LENGTH = 32;
    /** A lower boot epoch is accepted only after the sender was silent this long. */
    public static final long EPOCH_SILENCE_MILLIS = AuthReplay.WINDOW_MILLIS;
    private static final int HEADER_BYTES = 1 + 1 + 2 + 2 + 8 + 8 + 4 + 2;
    private static final byte[] LABEL = "sjws-raft-frame\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HKDF_INFO = "sjws-raft-frame-v1".getBytes(StandardCharsets.US_ASCII);
    private static final long RECONNECT_MILLIS = 50;
    private static final int CONNECT_TIMEOUT_MILLIS = 1_000;

    /** Why an inbound frame was dropped. */
    public enum Reason {
        MALFORMED,
        TAG,
        DEST,
        MEMBER,
        EPOCH,
        SEQ
    }

    /** The single place sockets are created. Security replaces it for mTLS. */
    public interface SocketFactory {
        ServerSocket listen(InetSocketAddress bind) throws IOException;

        Socket connect(InetSocketAddress to) throws IOException;

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
    private final AtomicInteger[] rejected = new AtomicInteger[Reason.values().length];
    private final ReentrantLock replayLock = new ReentrantLock();
    private final AuthReplay replay = new AuthReplay();
    private final ServerSocket server;
    private final Thread acceptor;
    private volatile BiConsumer<NodeId, SimTransport.Frame> handler;
    private volatile boolean open = true;

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
        return new TcpTransport(self, members, key, clock, queueCapacity, sockets);
    }

    private TcpTransport(NodeId self, List<Member> members, ClusterKey key, Clock clock, int queueCapacity,
            SocketFactory sockets) throws IOException {
        this.self = Objects.requireNonNull(self, "self");
        Objects.requireNonNull(key, "key");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sockets = Objects.requireNonNull(sockets, "sockets");
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity");
        }
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
            if (member.id().equals(self)) {
                bind = member.address();
            } else {
                peers.put(member.id(), new Peer(member.address(), queueCapacity));
            }
        }
        if (bind == null) {
            throw new IllegalArgumentException("self is not a member");
        }
        this.server = sockets.listen(bind);
        this.acceptor = Thread.ofVirtual().name("raft-accept-" + self.name()).start(this::accept);
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
            if (peer == null || cut.contains(to) || !open || !peer.queue.offer(frame)) {
                sendDrops.incrementAndGet();
            }
        };
    }

    @Override
    public int deliver(int max) {
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
            inboundSockets.add(socket);
            if (!open) {
                closeQuietly(socket);
                return;
            }
            Thread.ofVirtual().name("raft-read-" + self.name()).start(() -> read(socket));
        }
    }

    private void read(Socket socket) {
        try (socket; var in = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            var current = mac(currentKey);
            var previous = previousKey == null ? null : mac(previousKey);
            while (open) {
                int length = in.readInt();
                if (length < HEADER_BYTES + MAC_LENGTH || length > MAX_FRAME_BYTES) {
                    drop(Reason.MALFORMED);
                    return;
                }
                var bytes = new byte[length];
                in.readFully(bytes);
                var accepted = open(bytes, current, previous, clock.currentTimeMillis());
                if (accepted != null && !cut.contains(accepted.from) && !inbound.offer(accepted)) {
                    inboundDrops.incrementAndGet();
                }
            }
        } catch (IOException closed) {
            // Peer went away or we are closing; the peer's writer reconnects.
        } finally {
            inboundSockets.remove(socket);
        }
    }

    /** Checks one frame. Returns it, or null after counting why it was dropped. */
    Inbound open(byte[] bytes, Mac current, Mac previous, long nowMillis) {
        var buf = ByteBuffer.wrap(bytes);
        int version = buf.get();
        int keyId = buf.get();
        if (version != VERSION || !AuthReplay.keyIdOk(keyId)) {
            return drop(Reason.MALFORMED);
        }
        var mac = keyId == AuthReplay.KEY_CURRENT ? current : previous;
        if (mac == null || !tagMatches(mac, bytes)) {
            return drop(Reason.TAG);
        }
        int from = buf.getShort() & 0xFFFF;
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
        if (group.length > 0xFFFF || length > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("frame too large");
        }
        var bytes = new byte[length];
        ByteBuffer.wrap(bytes).put((byte) VERSION).put((byte) keyId).putShort((short) self.wire())
                .putShort((short) to.wire()).putLong(epoch).putLong(seq).putInt(frame.type())
                .putShort((short) group.length).put(group).put(body);
        mac.reset();
        mac.update(LABEL);
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

    private static boolean tagMatches(Mac mac, byte[] bytes) {
        mac.reset();
        mac.update(LABEL);
        mac.update(bytes, 0, bytes.length - MAC_LENGTH);
        var tag = mac.doFinal();
        return AuthReplay.tagEquals(tag, java.util.Arrays.copyOfRange(bytes, bytes.length - MAC_LENGTH,
                bytes.length));
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

    private static byte[] derive(byte[] ikm) {
        try {
            var kdf = KDF.getInstance("HKDF-SHA256");
            return kdf.deriveData(HKDFParameterSpec.ofExtract().addIKM(ikm).thenExpand(HKDF_INFO, MAC_LENGTH));
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
            this.writer = Thread.ofVirtual().name("raft-write-" + self.name()).start(this::write);
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
                try {
                    if (out == null) {
                        socket = sockets.connect(address);
                        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                    }
                    var bytes = seal(mac, HeartbeatAuthenticator.KEY_CURRENT, to, bootEpoch,
                            sendSeq.incrementAndGet(), frame);
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
            var current = socket;
            if (current != null) {
                closeQuietly(current);
            }
        }
    }
}
