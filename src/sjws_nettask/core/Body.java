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
public sealed interface Body permits Body.Empty, Body.Bytes, Body.FileBody {

    /** @return number of bytes the body has (also for HEAD responses, which don't send them) */
    long length();

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
     * Writer contract: open {@code realPath} with {@code LinkOption.NOFOLLOW_LINKS}; if
     * {@code expected.fileKey()} is non-null, check it against the opened path before sending the
     * head (a mismatch means the file was swapped: fail without sending anything); send exactly
     * {@code length} bytes starting at {@code offset}; if the file ends early, abort the
     * connection/stream rather than send a short body.
     *
     * @param realPath fully resolved path inside the document root
     * @param offset first byte to send
     * @param length number of bytes to send
     * @param expected file identity at resolution time
     */
    record FileBody(Path realPath, long offset, long length, Validator expected) implements Body {

        public FileBody {
            Objects.requireNonNull(realPath, "realPath");
            Objects.requireNonNull(expected, "expected");
            if (offset < 0 || length < 0) {
                throw new IllegalArgumentException("negative offset/length");
            }
        }
    }
}
