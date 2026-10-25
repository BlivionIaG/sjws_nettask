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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * #236 PR 1: the additive {@link TcpTransport} options the swarm peer links use. A {@link TcpTransport.Domain} other
 * than Raft refuses Raft frames under the same key (and the reverse), direct delivery hands frames over on the reader
 * thread, a per-domain frame cap applies before buffering, and a connection whose authenticated identity pins a wire
 * refuses frames claiming another one, is closed, and is held off.
 */
@Timeout(60)
class TcpTransportDomainTest {

    private static final ClusterKey KEY = ClusterKey.of("0123456789abcdef0123".getBytes(StandardCharsets.US_ASCII), null);
    static final TcpTransport.Domain OTHER = new TcpTransport.Domain("other", "sjws-other-frame\0", "sjws-other-frame-v1",
            4096);
    private static final GroupId G = new GroupId("t");
    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void closeAll() throws Exception {
        for (int i = open.size() - 1; i >= 0; i--) {
            open.get(i).close();
        }
    }

    private static List<Member> members(int n) throws IOException {
        var out = new ArrayList<Member>();
        for (int i = 1; i <= n; i++) {
            int port;
            try (var s = new ServerSocket(0)) {
                port = s.getLocalPort();
            }
            out.add(new Member(new NodeId(i, "n" + i), new SiteId("s"), new InetSocketAddress("127.0.0.1", port), 1L,
                    MemberState.ALIVE, false));
        }
        return out;
    }

    private TcpTransport start(TcpTransport t) {
        open.add(t);
        return t;
    }

    private static void await(BooleanSupplier ok, String what) throws InterruptedException {
        long end = System.nanoTime() + 10_000_000_000L;
        while (!ok.getAsBoolean()) {
            assertTrue(System.nanoTime() < end, what);
            Thread.sleep(5);
        }
    }

    @Test
    void directDeliveryHandsFramesOverOnTheReaderThread() throws Exception {
        var m = members(2);
        var got = new CopyOnWriteArrayList<String>();
        var a = start(TcpTransport.open(m.get(0).id(), m, KEY, Clock.SYSTEM, 8, TcpTransport.SocketFactory.PLAIN, OTHER,
                true));
        var b = start(TcpTransport.open(m.get(1).id(), m, KEY, Clock.SYSTEM, 8, TcpTransport.SocketFactory.PLAIN, OTHER,
                true));
        var rpc = a.attach(m.get(0).id(), (from, f) -> { });
        b.attach(m.get(1).id(), (from, f) -> got.add(from.name() + ":" + f.type() + ":" + Thread.currentThread().getName()));
        rpc.send(m.get(1).id(), new SimTransport.Frame(G, 5, new byte[] {1}));
        await(() -> !got.isEmpty(), "delivered without deliver()");
        assertTrue(got.get(0).startsWith("n1:5:"), got.toString());
        assertEquals(0, b.deliver(10));
    }

    @Test
    void sendNowWritesInlineWhenIdleAndKeepsOrderWithQueuedFrames() throws Exception {
        var m = members(2);
        var got = new CopyOnWriteArrayList<Integer>();
        var a = start(TcpTransport.open(m.get(0).id(), m, KEY, Clock.SYSTEM, 4096, TcpTransport.SocketFactory.PLAIN,
                OTHER, true));
        var b = start(TcpTransport.open(m.get(1).id(), m, KEY, Clock.SYSTEM, 8, TcpTransport.SocketFactory.PLAIN, OTHER,
                true));
        var rpc = a.attach(m.get(0).id(), (from, f) -> { });
        b.attach(m.get(1).id(), (from, f) -> got.add(java.nio.ByteBuffer.wrap(f.body()).getInt()));
        var to = m.get(1).id();
        a.sendNow(to, new SimTransport.Frame(G, 1, java.nio.ByteBuffer.allocate(4).putInt(0).array())); // not connected: queued
        await(() -> got.size() == 1, "first frame connects through the writer");
        for (int i = 1; i <= 2000; i++) {
            var f = new SimTransport.Frame(G, 1, java.nio.ByteBuffer.allocate(4).putInt(i).array());
            if (i % 3 == 0) {
                rpc.send(to, f);
            } else {
                a.sendNow(to, f);
            }
        }
        await(() -> got.size() == 2001, "every frame arrives, got " + got.size());
        for (int i = 0; i <= 2000; i++) {
            assertEquals(i, got.get(i), "wire order is send order");
        }
        assertEquals(0, b.rejected(TcpTransport.Reason.SEQ));
        long drops = a.sendDrops();
        a.sendNow(new NodeId(99, "nobody"), new SimTransport.Frame(G, 1, new byte[4]));
        assertEquals(drops + 1, a.sendDrops(), "unknown peer counted as a drop");
        var rm = members(2);
        var raft = start(TcpTransport.open(rm.get(0).id(), rm, KEY, Clock.SYSTEM, 8));
        assertThrows(IllegalStateException.class, () -> raft.sendNow(to, new SimTransport.Frame(G, 1, new byte[1])));
    }

    @Test
    void framesOfAnotherDomainFailTheTagCheckBothWays() throws Exception {
        var m = members(2);
        var raft = start(TcpTransport.open(m.get(0).id(), m, KEY, Clock.SYSTEM, 8));
        var other = start(TcpTransport.open(m.get(1).id(), m, KEY, Clock.SYSTEM, 8, TcpTransport.SocketFactory.PLAIN,
                OTHER, true));
        var got = new CopyOnWriteArrayList<Integer>();
        var toOther = raft.attach(m.get(0).id(), (from, f) -> got.add(-f.type()));
        var toRaft = other.attach(m.get(1).id(), (from, f) -> got.add(f.type()));
        for (int i = 0; i < 3; i++) {
            toOther.send(m.get(1).id(), new SimTransport.Frame(G, 7, new byte[] {1}));
            toRaft.send(m.get(0).id(), new SimTransport.Frame(G, 8, new byte[] {1}));
        }
        await(() -> other.rejected(TcpTransport.Reason.TAG) >= 3, "the other domain refuses Raft frames");
        await(() -> {
            raft.deliver(16);
            return raft.rejected(TcpTransport.Reason.TAG) >= 3;
        }, "Raft refuses the other domain's frames");
        raft.deliver(16);
        assertTrue(got.isEmpty(), got.toString());
    }

    @Test
    void aFrameOverTheDomainCapIsRefusedOnBothSides() throws Exception {
        var m = members(2);
        var a = start(TcpTransport.open(m.get(0).id(), m, KEY, Clock.SYSTEM, 8, TcpTransport.SocketFactory.PLAIN, OTHER,
                true));
        var big = start(TcpTransport.open(m.get(1).id(), m, KEY, Clock.SYSTEM, 8, TcpTransport.SocketFactory.PLAIN,
                new TcpTransport.Domain("other", OTHER.label(), OTHER.hkdfInfo(), 1 << 20), true));
        var got = new CopyOnWriteArrayList<Integer>();
        var rpc = a.attach(m.get(0).id(), (from, f) -> got.add(f.body().length));
        var bigRpc = big.attach(m.get(1).id(), (from, f) -> { });
        rpc.send(m.get(1).id(), new SimTransport.Frame(G, 1, new byte[5000]));
        await(() -> a.oversizeDrops() == 1, "sealing over the cap is refused");
        bigRpc.send(m.get(0).id(), new SimTransport.Frame(G, 1, new byte[5000]));
        bigRpc.send(m.get(0).id(), new SimTransport.Frame(G, 1, new byte[10]));
        await(() -> a.rejected(TcpTransport.Reason.MALFORMED) >= 1, "a length prefix over the cap is refused unread");
        assertTrue(got.isEmpty() || !got.contains(5000), got.toString());
        assertThrows(IllegalArgumentException.class, () -> new TcpTransport.Domain("x", "no-nul", "info", 4096));
        assertThrows(IllegalArgumentException.class, () -> new TcpTransport.Domain("x", "l\0", "", 4096));
        assertThrows(IllegalArgumentException.class, () -> new TcpTransport.Domain("x", "l\0", "i", 10));
        assertThrows(IllegalArgumentException.class,
                () -> new TcpTransport.Domain("x", "l\0", "i", TcpTransport.MAX_FRAME_BYTES + 1));
    }

    /** Plain sockets whose accepted side claims every connection belongs to {@code wire}. */
    private record Pinning(int wire) implements TcpTransport.SocketFactory {
        @Override
        public ServerSocket listen(InetSocketAddress bind) throws IOException {
            return PLAIN.listen(bind);
        }

        @Override
        public Socket connect(InetSocketAddress to) throws IOException {
            return PLAIN.connect(to);
        }

        @Override
        public int peerWire(Socket accepted) {
            if (wire < 0) {
                throw new IllegalStateException("unknown identity");
            }
            return wire;
        }
    }

    @Test
    void aPinnedConnectionAcceptsItsOwnWire() throws Exception {
        var m = members(2);
        var got = new CopyOnWriteArrayList<Integer>();
        var a = start(TcpTransport.open(m.get(0).id(), m, KEY, Clock.SYSTEM, 8, TcpTransport.SocketFactory.PLAIN, OTHER,
                true));
        var b = start(TcpTransport.open(m.get(1).id(), m, KEY, Clock.SYSTEM, 8, new Pinning(1), OTHER, true));
        var rpc = a.attach(m.get(0).id(), (from, f) -> { });
        b.attach(m.get(1).id(), (from, f) -> got.add(from.wire()));
        rpc.send(m.get(1).id(), new SimTransport.Frame(G, 1, new byte[] {1}));
        await(() -> got.size() == 1, "accepted");
        assertEquals(1, got.get(0));
        assertEquals(0, b.originMismatches());
    }

    @Test
    void aCorrectlyTaggedFrameClaimingAnotherWireIsRefusedClosedAndHeldOff() throws Exception {
        var m = members(3);
        var got = new CopyOnWriteArrayList<Integer>();
        // n1 holds the shared key but its connection's identity pins wire 3: a peer posing as another node
        var a = start(TcpTransport.open(m.get(0).id(), m, KEY, Clock.SYSTEM, 8, TcpTransport.SocketFactory.PLAIN, OTHER,
                true));
        var b = start(TcpTransport.open(m.get(1).id(), m, KEY, Clock.SYSTEM, 8, new Pinning(3), OTHER, true));
        var rpc = a.attach(m.get(0).id(), (from, f) -> { });
        b.attach(m.get(1).id(), (from, f) -> got.add(from.wire()));
        rpc.send(m.get(1).id(), new SimTransport.Frame(G, 1, new byte[] {1}));
        await(() -> b.originMismatches() == 1, "origin mismatch counted");
        assertEquals(1, b.rejected(TcpTransport.Reason.ORIGIN));
        // the writer reconnects; the pinned identity is held off, so its frames never reach the handler
        for (int i = 0; i < 20; i++) {
            rpc.send(m.get(1).id(), new SimTransport.Frame(G, 1, new byte[] {1}));
            Thread.sleep(20);
        }
        await(() -> b.originHeldOff() >= 1, "reconnects held off");
        assertTrue(got.isEmpty(), got.toString());
        assertEquals(1, b.originMismatches(), "held-off connections never get to send a frame");
    }

    @Test
    void aConnectionWhoseIdentityCannotBeMappedIsRefused() throws Exception {
        var m = members(2);
        var got = new CopyOnWriteArrayList<Integer>();
        var a = start(TcpTransport.open(m.get(0).id(), m, KEY, Clock.SYSTEM, 8, TcpTransport.SocketFactory.PLAIN, OTHER,
                true));
        var b = start(TcpTransport.open(m.get(1).id(), m, KEY, Clock.SYSTEM, 8, new Pinning(-1), OTHER, true));
        var rpc = a.attach(m.get(0).id(), (from, f) -> { });
        b.attach(m.get(1).id(), (from, f) -> got.add(from.wire()));
        rpc.send(m.get(1).id(), new SimTransport.Frame(G, 1, new byte[] {1}));
        await(() -> b.handshakeFailures() >= 1, "refused as a failed handshake");
        assertTrue(got.isEmpty());
    }
}
