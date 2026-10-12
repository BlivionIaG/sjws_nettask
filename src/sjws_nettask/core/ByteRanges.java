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

/**
 * Byte-range requests (RFC 9110 §14) for file representations, shared by every transport.
 * <p>
 * Supported: one {@code bytes=} range-spec ({@code a-b}, {@code a-}, {@code -n}) per request.
 * Anything else is ignored and the whole representation is served (200), as RFC 9110 §14.2
 * allows: several ranges (multipart is not implemented), unknown units, malformed specs,
 * {@code b < a}, positions longer than 18 digits. A valid single range that starts at or past the
 * end (or the suffix {@code -0}) is unsatisfiable (416). Zero-length files ignore ranges.
 * <p>
 * {@code If-Range} (§13.1.5): an entity-tag must be strong and equal to the current one (strong
 * comparison); an HTTP-date must equal Last-Modified, which counts as strong only when the ETag
 * is (see {@link EntityTag}: a version too young for a strong ETag also has a weak date).
 * Otherwise the Range is ignored (200).
 * <p>
 * Only GET has range semantics: the handler does not consult this for HEAD, so HEAD with Range
 * answers like a plain GET (the 200 head).
 */
public final class ByteRanges {

    private static final int MAX_DIGITS = 18; // fits a long

    private ByteRanges() {
    }

    /** Outcome of the Range and If-Range fields for one representation. */
    public sealed interface Decision permits Full, Partial, Unsatisfiable {
    }

    /** Serve the whole representation (no Range, or ignored). */
    public record Full() implements Decision {
        public static final Full INSTANCE = new Full();
    }

    /** Serve {@code length} bytes from {@code offset} (206). */
    public record Partial(long offset, long length, long size) implements Decision {

        public Partial {
            if (offset < 0 || length < 1 || offset + length > size) {
                throw new IllegalArgumentException("bad range " + offset + "+" + length + "/" + size);
            }
        }

        /** @return the Content-Range value, e.g. {@code bytes 0-99/1000} */
        public String contentRange() {
            return "bytes " + this.offset + "-" + (this.offset + this.length - 1) + "/" + this.size;
        }
    }

    /** No requested byte is inside the representation (416). */
    public record Unsatisfiable(long size) implements Decision {

        /** @return the Content-Range value of the 416, {@code bytes *}{@code /size} */
        public String contentRange() {
            return "bytes */" + this.size;
        }
    }

    /** Range + If-Range of a GET for the file described by {@code meta}. */
    public static Decision decide(RequestHeaders headers, FileMeta meta) {
        if (headers.range() == null) {
            return Full.INSTANCE;
        }
        if (headers.ifRange() != null && !ifRangeHolds(headers.ifRange(), meta)) {
            return Full.INSTANCE;
        }
        return parse(headers.range(), meta.validator().size());
    }

    /** @return true if the If-Range validator still names the current representation */
    static boolean ifRangeHolds(String ifRange, FileMeta meta) {
        var value = trimOws(ifRange);
        var current = meta.etag();
        boolean currentStrong = !current.startsWith("W/");
        if (value.startsWith("\"") || value.startsWith("W/")) {
            return currentStrong && value.equals(current); // a weak If-Range never matches
        }
        var date = HttpDate.parse(value);
        return date != null && currentStrong && date.equals(meta.lastModified());
    }

    /** Parses a Range field value for a representation of {@code size} bytes. */
    public static Decision parse(String range, long size) {
        if (range == null || size <= 0) {
            return Full.INSTANCE;
        }
        int eq = range.indexOf('=');
        if (eq <= 0 || !range.substring(0, eq).equalsIgnoreCase("bytes")) {
            return Full.INSTANCE;
        }
        String spec = null;
        for (var element : range.substring(eq + 1).split(",", -1)) {
            var e = trimOws(element);
            if (e.isEmpty()) {
                continue; // empty list elements are allowed (RFC 9110 §5.6.1)
            }
            if (spec != null) {
                return Full.INSTANCE; // several ranges: multipart/byteranges is not implemented
            }
            spec = e;
        }
        if (spec == null) {
            return Full.INSTANCE;
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return Full.INSTANCE;
        }
        var first = spec.substring(0, dash);
        var last = spec.substring(dash + 1);
        if (first.isEmpty()) { // suffix-range: the last n bytes
            long n = digits(last);
            if (n < 0) {
                return Full.INSTANCE;
            }
            if (n == 0) {
                return new Unsatisfiable(size);
            }
            long length = Math.min(n, size);
            return new Partial(size - length, length, size);
        }
        long a = digits(first);
        long b = last.isEmpty() ? Long.MAX_VALUE : digits(last);
        if (a < 0 || b < 0 || b < a) {
            return Full.INSTANCE; // invalid range-spec: the whole field is ignored
        }
        if (a >= size) {
            return new Unsatisfiable(size);
        }
        long end = Math.min(b, size - 1);
        return new Partial(a, end - a + 1, size);
    }

    /** @return the value of 1..18 ASCII digits, or -1 */
    private static long digits(String s) {
        if (s.isEmpty() || s.length() > MAX_DIGITS) {
            return -1;
        }
        long v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            v = v * 10 + (c - '0');
        }
        return v;
    }

    private static String trimOws(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && (s.charAt(start) == ' ' || s.charAt(start) == '\t')) {
            start++;
        }
        while (end > start && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t')) {
            end--;
        }
        return s.substring(start, end);
    }
}
