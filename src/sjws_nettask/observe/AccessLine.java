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
package sjws_nettask.observe;

import java.nio.charset.StandardCharsets;
import sjws_nettask.ServerConfig.AccessLogFormat;

/**
 * One access-log line, formatted straight into a single {@code byte[]} (the only allocation on the
 * logging path). Times are UTC. Common and combined follow the combined log format and append the
 * duration in microseconds; a zero body is {@code -} (CLF {@code %b}). JSON is one object per line
 * with {@code bytes} as a number and {@code duration_us}. Quotes, backslashes and controls in a
 * quoted CLF field become {@code ?} so a request cannot break the line.
 */
final class AccessLine {

    private static final byte[][] MONTH = new byte[][] {
            "Jan".getBytes(StandardCharsets.US_ASCII), "Feb".getBytes(StandardCharsets.US_ASCII),
            "Mar".getBytes(StandardCharsets.US_ASCII), "Apr".getBytes(StandardCharsets.US_ASCII),
            "May".getBytes(StandardCharsets.US_ASCII), "Jun".getBytes(StandardCharsets.US_ASCII),
            "Jul".getBytes(StandardCharsets.US_ASCII), "Aug".getBytes(StandardCharsets.US_ASCII),
            "Sep".getBytes(StandardCharsets.US_ASCII), "Oct".getBytes(StandardCharsets.US_ASCII),
            "Nov".getBytes(StandardCharsets.US_ASCII), "Dec".getBytes(StandardCharsets.US_ASCII)};

    private static final byte[] J_TIME = "{\"time\":\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] J_REMOTE = "\",\"remote\":\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] J_METHOD = "\",\"method\":\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] J_PATH = "\",\"path\":\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] J_PROTO = "\",\"protocol\":\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] J_STATUS = "\",\"status\":".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] J_BYTES = ",\"bytes\":".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] J_US = ",\"duration_us\":".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] J_REF = ",\"referer\":\"".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] J_UA = "\",\"user_agent\":\"".getBytes(StandardCharsets.US_ASCII);

    private AccessLine() {
    }

    /** {@code [02/Jan/2026:03:04:05 +0000]} — 28 bytes, formatted once a second off the request path. */
    static byte[] clfTime(long epochMillis) {
        long secs = Math.floorDiv(epochMillis, 1000L);
        int tod = (int) Math.floorMod(secs, 86400L);
        long ymd = ymd(Math.floorDiv(secs, 86400L));
        int year = (int) (ymd >>> 16);
        int month = (int) ((ymd >>> 8) & 0xff);
        int day = (int) (ymd & 0xff);
        var buf = new byte[28];
        buf[0] = '[';
        write2(buf, 1, day);
        buf[3] = '/';
        System.arraycopy(MONTH[month - 1], 0, buf, 4, 3);
        buf[7] = '/';
        write4(buf, 8, year);
        buf[12] = ':';
        write2(buf, 13, tod / 3600);
        buf[15] = ':';
        write2(buf, 16, (tod % 3600) / 60);
        buf[18] = ':';
        write2(buf, 19, tod % 60);
        buf[21] = ' ';
        buf[22] = '+';
        buf[23] = '0';
        buf[24] = '0';
        buf[25] = '0';
        buf[26] = '0';
        buf[27] = ']';
        return buf;
    }

    static byte[] format(AccessLogFormat format, long epochMillis, byte[] clfTime, String remote, String method,
            String target, String protocol, int status, long bytes, long durationNanos, String referer,
            String userAgent) {
        long us = durationNanos <= 0 ? 0 : durationNanos / 1000L;
        remote = orDash(remote);
        method = orDash(method);
        target = orDash(target);
        protocol = orDash(protocol);
        referer = orDash(referer);
        userAgent = orDash(userAgent);
        if (format == AccessLogFormat.JSON) {
            return json(epochMillis, remote, method, target, protocol, status, Math.max(0, bytes), us, referer,
                    userAgent);
        }
        return clf(format == AccessLogFormat.COMBINED, clfTime, remote, method, target, protocol, status, bytes, us,
                referer, userAgent);
    }

    private static byte[] clf(boolean combined, byte[] time, String remote, String method, String target,
            String protocol, int status, long bytes, long us, String referer, String userAgent) {
        int n = clfLen(remote) + 1 + 1 + 1 + 1 + 1 // " - - "
                + time.length + 2 // "] \""  the '[' is inside time
                + clfLen(method) + 1 + clfLen(target) + 1 + clfLen(protocol) + 2
                + digits(status) + 1 + (bytes <= 0 ? 1 : digits(bytes)) + 1 + digits(us) + 1;
        if (combined) {
            // space quote ref quote space quote ua quote
            n += 6 + clfLen(referer) + clfLen(userAgent);
        }
        var buf = new byte[n];
        int i = writeClf(buf, 0, remote);
        buf[i++] = ' ';
        buf[i++] = '-';
        buf[i++] = ' ';
        buf[i++] = '-';
        buf[i++] = ' ';
        System.arraycopy(time, 0, buf, i, time.length);
        i += time.length;
        buf[i++] = ' ';
        buf[i++] = '"';
        i = writeClf(buf, i, method);
        buf[i++] = ' ';
        i = writeClf(buf, i, target);
        buf[i++] = ' ';
        i = writeClf(buf, i, protocol);
        buf[i++] = '"';
        buf[i++] = ' ';
        i = writeDigits(buf, i, status);
        buf[i++] = ' ';
        if (bytes <= 0) {
            buf[i++] = '-';
        } else {
            i = writeDigits(buf, i, bytes);
        }
        if (combined) {
            buf[i++] = ' ';
            buf[i++] = '"';
            i = writeClf(buf, i, referer);
            buf[i++] = '"';
            buf[i++] = ' ';
            buf[i++] = '"';
            i = writeClf(buf, i, userAgent);
            buf[i++] = '"';
        }
        buf[i++] = ' ';
        i = writeDigits(buf, i, us);
        buf[i] = '\n';
        return buf;
    }

    private static byte[] json(long epochMillis, String remote, String method, String target, String protocol,
            int status, long bytes, long us, String referer, String userAgent) {
        int n = J_TIME.length + 24 + J_REMOTE.length + jsonLen(remote) + J_METHOD.length + jsonLen(method)
                + J_PATH.length + jsonLen(target) + J_PROTO.length + jsonLen(protocol) + J_STATUS.length
                + digits(status) + J_BYTES.length + digits(bytes) + J_US.length + digits(us) + J_REF.length
                + jsonLen(referer) + J_UA.length + jsonLen(userAgent) + 3;
        var buf = new byte[n];
        int i = copy(buf, 0, J_TIME);
        i = writeIso(buf, i, epochMillis);
        i = copy(buf, i, J_REMOTE);
        i = writeJson(buf, i, remote);
        i = copy(buf, i, J_METHOD);
        i = writeJson(buf, i, method);
        i = copy(buf, i, J_PATH);
        i = writeJson(buf, i, target);
        i = copy(buf, i, J_PROTO);
        i = writeJson(buf, i, protocol);
        i = copy(buf, i, J_STATUS);
        i = writeDigits(buf, i, status);
        i = copy(buf, i, J_BYTES);
        i = writeDigits(buf, i, bytes);
        i = copy(buf, i, J_US);
        i = writeDigits(buf, i, us);
        i = copy(buf, i, J_REF);
        i = writeJson(buf, i, referer);
        i = copy(buf, i, J_UA);
        i = writeJson(buf, i, userAgent);
        buf[i++] = '"';
        buf[i++] = '}';
        buf[i] = '\n';
        return buf;
    }

    /**
     * Year (high 16 bits; fine through year 65535), month and day of a Unix epoch day.
     * Howard Hinnant's {@code civil_from_days}. No allocation.
     */
    static long ymd(long epochDay) {
        long z = epochDay + 719468;
        long era = Math.floorDiv(z, 146097);
        long doe = Math.floorMod(z, 146097);
        long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        long y = yoe + era * 400;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long d = doy - (153 * mp + 2) / 5 + 1;
        long m = mp + (mp < 10 ? 3 : -9);
        if (m <= 2) {
            y++;
        }
        return (y << 16) | (m << 8) | d;
    }

    private static String orDash(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    private static int clfLen(String s) {
        return s.length();
    }

    /** Quoted CLF field: ASCII printable except {@code "} and {@code \} copied, everything else {@code ?}. */
    private static int writeClf(byte[] buf, int i, String s) {
        for (int c = 0; c < s.length(); c++) {
            char ch = s.charAt(c);
            buf[i++] = (ch >= 0x20 && ch < 0x7f && ch != '"' && ch != '\\') ? (byte) ch : (byte) '?';
        }
        return i;
    }

    private static int jsonLen(String s) {
        int n = 0;
        for (int c = 0; c < s.length(); c++) {
            char ch = s.charAt(c);
            if (ch == '"' || ch == '\\') {
                n += 2;
            } else if (ch < 0x20) {
                n += 6;
            } else if (ch < 0x80) {
                n += 1;
            } else if (ch < 0x800) {
                n += 2;
            } else if (Character.isHighSurrogate(ch) && c + 1 < s.length() && Character.isLowSurrogate(s.charAt(c + 1))) {
                n += 4;
                c++;
            } else {
                n += 3;
            }
        }
        return n;
    }

    private static int writeJson(byte[] buf, int i, String s) {
        for (int c = 0; c < s.length(); c++) {
            char ch = s.charAt(c);
            if (ch == '"' || ch == '\\') {
                buf[i++] = '\\';
                buf[i++] = (byte) ch;
            } else if (ch < 0x20) {
                buf[i++] = '\\';
                buf[i++] = 'u';
                buf[i++] = '0';
                buf[i++] = '0';
                buf[i++] = hex(ch >>> 4);
                buf[i++] = hex(ch);
            } else if (ch < 0x80) {
                buf[i++] = (byte) ch;
            } else if (ch < 0x800) {
                buf[i++] = (byte) (0xc0 | (ch >>> 6));
                buf[i++] = (byte) (0x80 | (ch & 0x3f));
            } else if (Character.isHighSurrogate(ch) && c + 1 < s.length() && Character.isLowSurrogate(s.charAt(c + 1))) {
                int cp = Character.toCodePoint(ch, s.charAt(++c));
                buf[i++] = (byte) (0xf0 | (cp >>> 18));
                buf[i++] = (byte) (0x80 | ((cp >>> 12) & 0x3f));
                buf[i++] = (byte) (0x80 | ((cp >>> 6) & 0x3f));
                buf[i++] = (byte) (0x80 | (cp & 0x3f));
            } else {
                buf[i++] = (byte) (0xe0 | (ch >>> 12));
                buf[i++] = (byte) (0x80 | ((ch >>> 6) & 0x3f));
                buf[i++] = (byte) (0x80 | (ch & 0x3f));
            }
        }
        return i;
    }

    private static byte hex(int v) {
        v &= 0xf;
        return (byte) (v < 10 ? '0' + v : 'a' + v - 10);
    }

    private static int copy(byte[] buf, int i, byte[] src) {
        System.arraycopy(src, 0, buf, i, src.length);
        return i + src.length;
    }

    private static int writeIso(byte[] buf, int i, long epochMillis) {
        long secs = Math.floorDiv(epochMillis, 1000L);
        int ms = (int) Math.floorMod(epochMillis, 1000L);
        int tod = (int) Math.floorMod(secs, 86400L);
        long ymd = ymd(Math.floorDiv(secs, 86400L));
        i = write4(buf, i, (int) (ymd >>> 16));
        buf[i++] = '-';
        i = write2(buf, i, (int) ((ymd >>> 8) & 0xff));
        buf[i++] = '-';
        i = write2(buf, i, (int) (ymd & 0xff));
        buf[i++] = 'T';
        i = write2(buf, i, tod / 3600);
        buf[i++] = ':';
        i = write2(buf, i, (tod % 3600) / 60);
        buf[i++] = ':';
        i = write2(buf, i, tod % 60);
        buf[i++] = '.';
        buf[i++] = (byte) ('0' + ms / 100);
        buf[i++] = (byte) ('0' + (ms / 10) % 10);
        buf[i++] = (byte) ('0' + ms % 10);
        buf[i++] = 'Z';
        return i;
    }

    private static int digits(long v) {
        int n = 1;
        while (v >= 10) {
            v /= 10;
            n++;
        }
        return n;
    }

    private static int writeDigits(byte[] buf, int i, long v) {
        int n = digits(v);
        for (int p = i + n - 1; p >= i; p--) {
            buf[p] = (byte) ('0' + (v % 10));
            v /= 10;
        }
        return i + n;
    }

    private static int write2(byte[] buf, int i, int v) {
        buf[i] = (byte) ('0' + v / 10);
        buf[i + 1] = (byte) ('0' + v % 10);
        return i + 2;
    }

    private static int write4(byte[] buf, int i, int v) {
        buf[i] = (byte) ('0' + (v / 1000) % 10);
        buf[i + 1] = (byte) ('0' + (v / 100) % 10);
        buf[i + 2] = (byte) ('0' + (v / 10) % 10);
        buf[i + 3] = (byte) ('0' + v % 10);
        return i + 4;
    }
}
