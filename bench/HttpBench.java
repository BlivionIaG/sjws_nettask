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
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import sjws_nettask.ServerConfig;
import sjws_nettask.SjwsServer;

/**
 * Closed-loop HTTP/1.1 keep-alive benchmark against an in-process SjwsServer (HTTP/1 only).
 * Usage: java -cp target/classes bench/HttpBench.java [connections=64] [seconds=10] [bodyBytes=1024]
 * Each connection sends one GET at a time and waits for the full response; latency = send to last byte.
 */
public class HttpBench {
    public static void main(String[] a) throws Exception {
        int conns = a.length > 0 ? Integer.parseInt(a[0]) : 64;
        int secs = a.length > 1 ? Integer.parseInt(a[1]) : 10;
        int size = a.length > 2 ? Integer.parseInt(a[2]) : 1024;
        Path root = Files.createTempDirectory("httpbench");
        Files.write(root.resolve("f.bin"), new byte[size]);
        try (var server = SjwsServer.start(ServerConfig.builder(root).port(0).maxConnections(conns * 2).build())) {
            int port = server.tcpPort();
            byte[] req = ("GET /f.bin HTTP/1.1\r\nHost: b\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            run(port, req, conns, 2, null); // warm-up
            var lat = new ConcurrentLinkedQueue<long[]>();
            long t0 = System.nanoTime();
            long n = run(port, req, conns, secs, lat);
            double el = (System.nanoTime() - t0) / 1e9;
            long[] all = lat.stream().flatMapToLong(Arrays::stream).filter(x -> x > 0).sorted().toArray();
            System.out.printf("http1 conns=%d body=%dB requests=%d throughput=%.0f req/s p50=%.1fus p99=%.1fus p999=%.1fus%n",
                    conns, size, n, n / el, pct(all, .50), pct(all, .99), pct(all, .999));
        }
    }

    static double pct(long[] s, double p) { return s.length == 0 ? 0 : s[(int) Math.min(s.length - 1, p * s.length)] / 1e3; }

    static long run(int port, byte[] req, int conns, int secs, Queue<long[]> lat) throws Exception {
        var count = new LongAdder();
        long end = System.nanoTime() + secs * 1_000_000_000L;
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int c = 0; c < conns; c++) {
                ex.submit(() -> {
                    long[] l = new long[1 << 20]; int i = 0;
                    try (var s = new Socket("127.0.0.1", port)) {
                        s.setTcpNoDelay(true);
                        var in = new BufferedInputStream(s.getInputStream(), 65536);
                        var out = s.getOutputStream();
                        while (System.nanoTime() < end) {
                            long st = System.nanoTime();
                            out.write(req); out.flush();
                            readResponse(in);
                            if (i < l.length) l[i++] = System.nanoTime() - st;
                            count.increment();
                        }
                    }
                    if (lat != null) lat.add(Arrays.copyOf(l, i));
                    return null;
                });
            }
        }
        return count.sum();
    }

    static void readResponse(InputStream in) throws IOException {
        long len = -1; var line = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            if (b == '\n') {
                String h = line.toString().trim(); line.setLength(0);
                if (h.isEmpty()) break;
                if (h.regionMatches(true, 0, "content-length:", 0, 15)) len = Long.parseLong(h.substring(15).trim());
            } else line.append((char) b);
        }
        if (len < 0) throw new IOException("no content-length");
        in.skipNBytes(len);
    }
}
