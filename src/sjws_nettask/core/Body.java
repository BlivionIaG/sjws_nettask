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
package sjws_nettask.core;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Response body. Writers must handle every variant.
 */
public sealed interface Body permits Body.Empty, Body.Bytes, Body.FileBody, Body.Stream {

    /**
     * @return number of bytes the body has (also for HEAD responses, which don't send them), or -1
     *         for a {@link Stream} of unknown length
     */
    long length();

    /**
     * Content pulled from a producer (a proxied upstream), with backpressure: the writer asks for
     * the next buffer only after the previous one was handed to the transport.
     * <p>
     * Writer contract: call {@link Source#close()} exactly once in every case (sent, HEAD, error,
     * aborted). With a known {@code length}, send exactly that many bytes or abort the
     * connection/stream (never a short response). With an unknown length (-1): HTTP/1.1 keep-alive
     * uses chunked framing, HTTP/1.0 or a closing connection is delimited by close, HTTP/2 and
     * HTTP/3 end the stream. A {@link java.io.IOException} from {@link Source#next()} after the head
     * was sent aborts the connection/stream.
     *
     * @param length body length in bytes, or -1 when unknown
     */
    record Stream(long length, Source source) implements Body {

        public Stream {
            Objects.requireNonNull(source, "source");
            if (length < -1) {
                throw new IllegalArgumentException("length < -1");
            }
        }

        /** Pull-style producer. Not thread-safe: one writer thread calls it. */
        public interface Source extends AutoCloseable {

            /**
             * @return the next non-empty chunk (valid until the next call or {@link #close()}; the
             *         writer copies or sends it before asking again), or null at the end
             */
            ByteBuffer next() throws java.io.IOException;

            /** Releases the producer (e.g. returns a pooled connection, or discards it if unfinished). */
            @Override
            void close();
        }
    }

    /** No content. */
    record Empty() implements Body {

        public static final Empty INSTANCE = new Empty();

        @Override
        public long length() {
            return 0;
        }
    }

    /**
     * In-memory content.
     *
     * @param data read-only view; writers must use {@code data.duplicate()} and never change its position
     */
    record Bytes(ByteBuffer data) implements Body {

        public Bytes {
            data = Objects.requireNonNull(data, "data").slice().asReadOnlyBuffer();
        }

        public static Bytes of(byte[] bytes) {
            return new Bytes(ByteBuffer.wrap(bytes));
        }

        @Override
        public long length() {
            return this.data.remaining();
        }
    }

    /**
     * A region of a file, streamed by the writer.
     * <p>
     * Writer contract: open the file with {@link SafeOpen#open} (never by path), which fails if
     * {@code realPath} no longer names, inside {@code root}, the file identified by {@code expected}
     * (swapped by a rename or a symlink in any component): then fail without sending anything; send
     * exactly {@code length} bytes starting at {@code offset}; if the file ends early, abort the
     * connection/stream rather than send a short body.
     *
     * @param realPath fully resolved path inside the document root
     * @param offset first byte to send
     * @param length number of bytes to send
     * @param expected file identity at resolution time
     * @param root real path of the document root the file was resolved in (a prefix of {@code realPath});
     *        the writer opens every component below it without following symlinks
     * @param allowUnlistable below a directory that can be searched but not read, open by path (weaker,
     *        see {@link SafeOpen}) instead of refusing with 403
     */
    record FileBody(Path realPath, long offset, long length, Validator expected, Path root, boolean allowUnlistable)
            implements Body {

        public FileBody {
            Objects.requireNonNull(realPath, "realPath");
            Objects.requireNonNull(expected, "expected");
            Objects.requireNonNull(root, "root");
            if (offset < 0 || length < 0) {
                throw new IllegalArgumentException("negative offset/length");
            }
            if (!realPath.isAbsolute() || !realPath.startsWith(root)) {
                throw new IllegalArgumentException("realPath must be absolute and inside root");
            }
        }

        /** Refuses files below unlistable directories (the default). */
        public FileBody(Path realPath, long offset, long length, Validator expected, Path root) {
            this(realPath, offset, length, expected, root, false);
        }

        /** A file outside any document root: components are checked from the file-system root. */
        public FileBody(Path realPath, long offset, long length, Validator expected) {
            this(realPath, offset, length, expected, Objects.requireNonNull(realPath, "realPath").getRoot() == null
                    ? realPath : realPath.getRoot());
        }
    }
}
