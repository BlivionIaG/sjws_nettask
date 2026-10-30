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

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import sjws_nettask.cluster.Clock;
import sjws_nettask.cluster.ClusterKey;
import sjws_nettask.cluster.GroupId;
import sjws_nettask.cluster.Member;
import sjws_nettask.cluster.MemberState;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.SimTransport;
import sjws_nettask.cluster.SiteId;
import sjws_nettask.cluster.TcpTransport;
import sjws_nettask.swarm.peer.PeerLink;

/**
 * #274 B2: the HA messaging member links have their own frame domain. Under the same key, a frame sealed for the CP
 * voter links ({@link TcpTransport.Domain#RAFT}) fails the tag check on an HA messaging transport, and the reverse.
 */
@Timeout(60)
class HaMessagingDomainTest {

    private static final ClusterKey KEY = ClusterKey.of(HaMessagingTest.KEY, null);
    private static final GroupId G = new GroupId("msg/t");
    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void closeAll() throws Exception {
        for (int i = open.size() - 1; i >= 0; i--) {
            open.get(i).close();
        }
    }

    /**
     * Members whose listen ports are already bound. {@link #factory} hands those sockets to
     * {@link TcpTransport} so nothing races to steal the port between probe and listen.
     */
    private static final class Reserved implements AutoCloseable {
        final List<Member> members;
        final TcpTransport.SocketFactory factory;
        private final Map<Integer, ServerSocket> held;

        Reserved(List<Member> members, Map<Integer, ServerSocket> held, TcpTransport.SocketFactory factory) {
            this.members = members;
            this.held = held;
            this.factory = factory;
        }

        @Override
        public void close() throws IOException {
            for (var s : held.values()) {
                s.close();
            }
            held.clear();
        }
    }

    private static Reserved members(int n) throws IOException {
        var out = new ArrayList<Member>();
        var held = new LinkedHashMap<Integer, ServerSocket>();
        for (int i = 1; i <= n; i++) {
            var s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress("127.0.0.1", 0), 16);
            held.put(s.getLocalPort(), s);
            out.add(new Member(new NodeId(i, "n" + i), new SiteId("msg"),
                    new InetSocketAddress("127.0.0.1", s.getLocalPort()), 1L, MemberState.ALIVE, false));
        }
        TcpTransport.SocketFactory factory = new TcpTransport.SocketFactory() {
            @Override
            public ServerSocket listen(InetSocketAddress bind) throws IOException {
                var s = held.remove(bind.getPort());
                if (s == null) {
                    throw new IOException("no reserved socket for " + bind);
                }
                return s;
            }

            @Override
            public Socket connect(InetSocketAddress to) throws IOException {
                return TcpTransport.SocketFactory.PLAIN.connect(to);
            }
        };
        return new Reserved(out, held, factory);
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
    void theDomainIsItsOwn() {
        var d = HaMessaging.DOMAIN;
        assertEquals("sjws-msgrepl-frame\0", d.label());
        assertEquals("sjws-msgrepl-frame-v1", d.hkdfInfo());
        for (var other : List.of(TcpTransport.Domain.RAFT, PeerLink.DOMAIN)) {
            assertNotEquals(other.label(), d.label());
            assertNotEquals(other.hkdfInfo(), d.hkdfInfo());
            assertNotEquals(other.name(), d.name());
        }
    }

    @Test
    void cpRaftAndHaMessagingFramesFailTheTagCheckBothWaysUnderTheSameKey() throws Exception {
        var reserved = members(2);
        open.add(reserved);
        var m = reserved.members;
        var cp = start(TcpTransport.open(m.get(0).id(), m, KEY, Clock.SYSTEM, 8, reserved.factory));
        var msg = start(TcpTransport.open(m.get(1).id(), m, KEY, Clock.SYSTEM, 8, reserved.factory,
                HaMessaging.DOMAIN, false));
        var got = new CopyOnWriteArrayList<String>();
        var toMsg = cp.attach(m.get(0).id(), (from, f) -> got.add("cp got " + f.type()));
        var toCp = msg.attach(m.get(1).id(), (from, f) -> got.add("msg got " + f.type()));
        for (int i = 0; i < 3; i++) {
            toMsg.send(m.get(1).id(), new SimTransport.Frame(G, 7, new byte[] {1}));
            toCp.send(m.get(0).id(), new SimTransport.Frame(G, 8, new byte[] {1}));
        }
        await(() -> {
            msg.deliver(16);
            return msg.rejected(TcpTransport.Reason.TAG) >= 3;
        }, "HA messaging refuses CP Raft frames");
        await(() -> {
            cp.deliver(16);
            return cp.rejected(TcpTransport.Reason.TAG) >= 3;
        }, "CP Raft refuses HA messaging frames");
        cp.deliver(16);
        msg.deliver(16);
        assertTrue(got.isEmpty(), got.toString());
    }

    @Test
    void twoHaMessagingTransportsStillTalk() throws Exception {
        var reserved = members(2);
        open.add(reserved);
        var m = reserved.members;
        var a = start(TcpTransport.open(m.get(0).id(), m, KEY, Clock.SYSTEM, 8, reserved.factory,
                HaMessaging.DOMAIN, false));
        var b = start(TcpTransport.open(m.get(1).id(), m, KEY, Clock.SYSTEM, 8, reserved.factory,
                HaMessaging.DOMAIN, false));
        var got = new CopyOnWriteArrayList<Integer>();
        var rpc = a.attach(m.get(0).id(), (from, f) -> { });
        b.attach(m.get(1).id(), (from, f) -> got.add(f.type()));
        rpc.send(m.get(1).id(), new SimTransport.Frame(G, 5, "x".getBytes(StandardCharsets.US_ASCII)));
        await(() -> {
            b.deliver(16);
            return !got.isEmpty();
        }, "same-domain frame delivered");
        assertEquals(List.of(5), got);
        assertEquals(0, b.rejected(TcpTransport.Reason.TAG));
    }
}
