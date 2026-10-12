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
package sjws_nettask;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;

import sjws_nettask.http1.Http1Writer;

/**
 * Unbuffered output of one HTTP/1.1 connection: {@link Http1Writer} already hands over whole
 * responses (head + small body in one array), so every write goes straight to the blocking
 * channel and nothing is held between responses. Writes go out in slices of at most
 * {@link #WRITE_CHUNK} (bounds the JDK's temporary direct buffer) and each slice that makes
 * progress pushes the connection's write deadline forward. Large files go through
 * {@link FileChannel#transferTo} (sendfile).
 */
final class ChannelOutput extends OutputStream implements Http1Writer.FileSink {

    static final int WRITE_CHUNK = 64 * 1024;

    /**
     * Most bytes requested from one transferTo (sendfile) call. Measured in S5 (3 reps, medians):
     * 256 KiB / 1 MiB changed 50 MB downloads by +4.8% / +1.6% (100 connections) and -3.4% / +2.4%
     * (1000), under the 5% bar, so it stays at 64 KiB. (Uncached ~100 KB files would gain ~6%: one
     * call instead of two.) On a virtual thread each call returns after partial progress anyway,
     * so the size does not affect the write deadline.
     */
    static final int TRANSFER_CHUNK = 64 * 1024;

    /** Bytes sent with a parking write when sendfile reports a full socket buffer. */
    static final int BLOCKING_SLICE = 16 * 1024;

    /** Zero-byte file reads in a row (not expected on a regular file) before giving up. */
    static final int MAX_STALLS = 3;

    private final SocketChannel channel;
    private final ConnectionReaper.Timer timer;

    ChannelOutput(SocketChannel channel, ConnectionReaper.Timer timer) {
        this.channel = channel;
        this.timer = timer;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        var buf = ByteBuffer.wrap(b, off, len);
        while (buf.hasRemaining()) {
            this.timer.writing();
            int limit = buf.limit();
            buf.limit(Math.min(limit, buf.position() + WRITE_CHUNK));
            this.channel.write(buf);
            buf.limit(limit);
        }
    }

    /**
     * Sends {@code count} bytes of {@code source} from {@code position} with sendfile.
     * <p>
     * On a virtual thread {@link FileChannel#transferTo} does not park when the socket's send
     * buffer is full: it returns 0, exactly as at the end of a file. So a 0 is resolved with
     * {@link FileChannel#size()}: at or past the end, the file shrank (the response can't be
     * completed); otherwise the next {@link #BLOCKING_SLICE} bytes go through the ordinary blocking
     * {@code write}, which parks until the client reads (under the write deadline), and sendfile
     * resumes. Every iteration makes progress, blocks, or fails: it never spins.
     *
     * @throws IOException if the file ends before {@code position + count} (it shrank: the caller
     *         closes the connection) or the transfer fails
     */
    @Override
    public void transferFrom(FileChannel source, long position, long count) throws IOException {
        long pos = position;
        long end = position + count;
        int stalls = 0;
        ByteBuffer slice = null; // only for clients slower than the network path
        while (pos < end) {
            this.timer.writing();
            long n = source.transferTo(pos, Math.min(TRANSFER_CHUNK, end - pos), this.channel);
            if (n > 0) {
                pos += n;
                stalls = 0;
                continue;
            }
            if (source.size() <= pos) {
                throw shrank(pos - position, count);
            }
            if (slice == null) {
                slice = ByteBuffer.allocate(BLOCKING_SLICE);
            }
            slice.clear().limit((int) Math.min(BLOCKING_SLICE, end - pos));
            int r = source.read(slice, pos);
            if (r < 0) {
                throw shrank(pos - position, count);
            }
            if (r == 0) {
                if (++stalls >= MAX_STALLS) {
                    throw new IOException("transferTo made no progress");
                }
                continue;
            }
            slice.flip();
            while (slice.hasRemaining()) {
                this.timer.writing();
                this.channel.write(slice); // parks until the socket is writable
            }
            pos += r;
            stalls = 0;
        }
    }

    private static IOException shrank(long sent, long count) {
        return new IOException("file shrank during transfer (" + sent + " of " + count + " bytes sent)");
    }

    @Override
    public void flush() {
        // nothing is buffered
    }

    @Override
    public void close() {
        // the connection owns the channel
    }
}
