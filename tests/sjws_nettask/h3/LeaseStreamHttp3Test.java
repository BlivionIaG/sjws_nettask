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
package sjws_nettask.h3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.SjwsServer;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.swarm.SwarmMountForTests;
import sjws_nettask.tls.TlsMaterial;

/**
 * #261 Q1: over HTTP/3, {@code :claim} carries {@code sjws-lease-stream} and {@code sjws-stream-window} for a batch with
 * a stream task (once each, entry per LEASE), none for a unary-only batch. Run with the advertised window of a small config
 * and at the defaults (64/256). HTTP/3 ignores request bodies today ({@code Http3RequestHandler}), so the tasks are
 * submitted in-process and only the body-less {@code :claim} goes over QUIC; {@code :chunk} over HTTP/1.1 is covered by
 * {@code LeaseStreamHeaderTest} and the Python e2e.
 */
@Tag("h3")
class LeaseStreamHttp3Test {

    private static final String WIRE = "application/vnd.sjws.swarm.v1";

    @TempDir
    Path root;

    @ParameterizedTest
    @CsvSource({"4, 4, true", "64, 256, false"})
    void claimHeadersOverHttp3(int window, int sinkChunks, boolean credit) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Http3Server.isAvailable(),
                () -> "native QUIC unavailable: " + Http3Server.unavailabilityCause());
        var tls = TlsMaterial.selfSigned("localhost");
        var cert = Files.writeString(this.root.resolve("cert.pem"),
                SjwsServerHttp3Test.pem("CERTIFICATE", tls.certificate().getEncoded()));
        var key = Files.writeString(this.root.resolve("key.pem"),
                SjwsServerHttp3Test.pem("PRIVATE KEY", tls.key().getEncoded()));
        var config = ServerConfig.builder(this.root).port(0).h3Enabled(true).h3Port(0).h3Host("127.0.0.1")
                .tlsCert(cert).tlsKey(key).build();
        try (var mount = SwarmMountForTests.streaming(this.root.resolve("swarm"), window, sinkChunks, credit);
                var server = SjwsServer.start(config, RequestFilters.create(), null, List.of(mount.contributor()));
                var client = new H3TestClient(tls.certificate())) {
            assertNull(server.h3Failure());
            var quic = client.connect(new InetSocketAddress("127.0.0.1", server.udpPort()));
            // unary only: neither header
            assertEquals(202, mount.submit("gen", false));
            var unary = post(quic, "/swarm/v1/tasks/gen:claim?visibilityMs=60000", new byte[0]);
            assertEquals(200, unary.status());
            assertNull(unary.header("sjws-lease-stream"));
            assertNull(unary.header("sjws-stream-window"));
            // unary then stream in one batch
            assertEquals(202, mount.submit("gen", false));
            assertEquals(201, mount.submit("gen", true));
            var r = post(quic, "/swarm/v1/tasks/gen:claim?visibilityMs=60000&max=4", new byte[0]);
            assertEquals(200, r.status());
            assertEquals(1, r.headerNames().stream().filter("sjws-lease-stream"::equals).count(), "one field");
            assertEquals(1, r.headerNames().stream().filter("sjws-stream-window"::equals).count(), "one field");
            assertEquals(Integer.toString(window), r.header("sjws-stream-window"));
            var leases = leaseIds(r.body());
            var entries = r.header("sjws-lease-stream").split(",", -1);
            assertEquals(leases.size(), entries.length, "one entry per LEASE");
            assertEquals(2, leases.size());
            assertEquals("-", entries[0]);
            assertTrue(entries[1].matches("[0-9a-f]{32}/1"), entries[1]);
        }
    }

    private static H3TestClient.H3Response post(io.netty.handler.codec.quic.QuicChannel quic, String path, byte[] body)
            throws Exception {
        var r = H3TestClient.request(quic, "POST", path,
                Map.of("content-type", WIRE, "content-length", Integer.toString(body.length)), body, null)
                .get(30, TimeUnit.SECONDS);
        if (r.status() >= 400) {
            System.err.println("h3 " + path + " -> " + r.status() + " " + new String(r.body()));
        }
        return r;
    }

    /** The lease ids of the LEASE frames in a claim body ({@code [idLen:u16][leaseId][deadline:i64][body]}). */
    private static List<String> leaseIds(byte[] body) {
        var in = ByteBuffer.wrap(body);
        var out = new java.util.ArrayList<String>();
        while (in.remaining() >= 6) {
            in.get();
            in.get();
            in.get();
            int n = ((in.get() & 0xff) << 16) | ((in.get() & 0xff) << 8) | (in.get() & 0xff);
            var p = new byte[n];
            in.get(p);
            var pb = ByteBuffer.wrap(p);
            var id = new byte[pb.getShort()];
            pb.get(id);
            out.add(new String(id, java.nio.charset.StandardCharsets.UTF_8));
        }
        return out;
    }
}
