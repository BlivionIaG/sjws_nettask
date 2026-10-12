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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32C;

/**
 * Prototype append-only segmented log write path (NOT server code): records framed as
 * [len:int][crc32c:int][offset:long][payload], 1 GiB segments, sparse offset index every 4 KiB.
 * Producers hand records to a single writer thread (single-writer principle). Modes:
 *   none   - write(), never fsync (durability = OS page cache)
 *   each   - fsync (FileChannel.force(false)) after every record before acking
 *   group  - group commit: writer drains everything queued, one write + one fsync, then acks the batch
 * Usage: java bench/LogBench.java [dir] [mode] [producers=8] [records=200000] [payload=256]
 * Latency = enqueue to ack (durable for each/group).
 */
public class LogBench {
    record Rec(byte[] payload, CompletableFuture<Long> ack, long t0) {}

    static final long SEGMENT = 1L << 30;
    static final int INDEX_EVERY = 4096;

    public static void main(String[] a) throws Exception {
        Path dir = Path.of(a.length > 0 ? a[0] : "/tmp/logbench");
        String mode = a.length > 1 ? a[1] : "group";
        int producers = a.length > 2 ? Integer.parseInt(a[2]) : 8;
        int records = a.length > 3 ? Integer.parseInt(a[3]) : 200_000;
        int payload = a.length > 4 ? Integer.parseInt(a[4]) : 256;
        Files.createDirectories(dir);
        try (var s = Files.list(dir)) { for (var p : s.toList()) Files.delete(p); }

        var q = new LinkedBlockingQueue<Rec>(65536);
        var log = new Log(dir);
        var writer = new Thread(() -> writerLoop(q, log, mode), "log-writer");
        writer.setDaemon(true);
        writer.start();

        long[] lat = new long[records];
        int per = records / producers;
        long t0 = System.nanoTime();
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int p = 0; p < producers; p++) {
                int base = p * per;
                ex.submit(() -> {
                    byte[] body = new byte[payload];
                    for (int i = 0; i < per; i++) {
                        var r = new Rec(body, new CompletableFuture<>(), System.nanoTime());
                        q.put(r);
                        r.ack().get(); // closed loop: one in flight per producer
                        lat[base + i] = System.nanoTime() - r.t0();
                    }
                    return null;
                });
            }
        }
        double el = (System.nanoTime() - t0) / 1e9;
        int n = per * producers;
        long[] s = Arrays.copyOf(lat, n); Arrays.sort(s);
        System.out.printf("log mode=%s producers=%d payload=%dB records=%d throughput=%.0f rec/s (%.1f MB/s) fsyncs=%d p50=%.1fus p99=%.1fus p999=%.1fus%n",
                mode, producers, payload, n, n / el, n * (payload + 16.0) / el / 1e6, log.fsyncs,
                s[n / 2] / 1e3, s[(int) (n * .99)] / 1e3, s[Math.min(n - 1, (int) (n * .999))] / 1e3);
        log.close();
    }

    static void writerLoop(BlockingQueue<Rec> q, Log log, String mode) {
        var batch = new ArrayList<Rec>(4096);
        try {
            while (true) {
                batch.add(q.take());
                if (!mode.equals("each")) q.drainTo(batch, 4095);
                long[] offs = new long[batch.size()];
                for (int i = 0; i < offs.length; i++) {
                    offs[i] = log.append(batch.get(i).payload());
                    if (mode.equals("each")) log.flush(true);
                }
                if (!mode.equals("each")) log.flush(mode.equals("group"));
                for (int i = 0; i < offs.length; i++) batch.get(i).ack().complete(offs[i]); // ack only after the write (+fsync)
                batch.clear();
            }
        } catch (Exception e) {
            batch.forEach(r -> r.ack().completeExceptionally(e));
        }
    }

    /** Segmented log; one writer thread only. */
    static final class Log {
        final Path dir; FileChannel seg, idx; long base, next, segBytes, lastIndexed = -INDEX_EVERY; long fsyncs;
        final ByteBuffer buf = ByteBuffer.allocateDirect(4 << 20); final ByteBuffer ib = ByteBuffer.allocate(16);
        final CRC32C crc = new CRC32C();

        Log(Path dir) throws IOException { this.dir = dir; roll(); }

        void roll() throws IOException {
            if (seg != null) { flush(true); seg.close(); idx.close(); }
            base = next; segBytes = 0; lastIndexed = -INDEX_EVERY;
            String name = String.format("%020d", base);
            seg = FileChannel.open(dir.resolve(name + ".log"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            idx = FileChannel.open(dir.resolve(name + ".index"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }

        long append(byte[] p) throws IOException {
            int size = 16 + p.length;
            if (segBytes + buf.position() + size > SEGMENT) { flush(false); roll(); }
            if (buf.remaining() < size) flush(false);
            long pos = segBytes + buf.position();
            if (pos - lastIndexed >= INDEX_EVERY) {
                ib.clear(); ib.putLong(next - base).putLong(pos).flip(); idx.write(ib); lastIndexed = pos;
            }
            crc.reset(); crc.update(p);
            buf.putInt(p.length).putInt((int) crc.getValue()).putLong(next).put(p);
            return next++;
        }

        /** Writes buffered records; fsyncs the segment if {@code durable} (index is rebuilt on recovery). */
        void flush(boolean durable) throws IOException {
            buf.flip();
            while (buf.hasRemaining()) segBytes += seg.write(buf);
            buf.clear();
            if (durable) { seg.force(false); fsyncs++; }
        }

        void close() throws IOException { flush(true); seg.close(); idx.close(); }
    }
}
