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
package sjws_nettask.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Reads a join-token file without putting the secret into a {@link String}. The file is
 * {@code token-id=} and {@code secret=} lines, mode 0600 or 0400, and not a symlink.
 */
final class TokenFiles {

    static final int MAX_TOKEN_BYTES = 4096;
    static final int MAX_OTHER_BYTES = 65_536;

    private TokenFiles() {
    }

    static JoinMaterial readJoin(java.nio.file.Path path) throws IOException {
        PosixMode.refuseSymlink(path);
        PosixMode.requireOwnerOnly(path);
        byte[] raw = readCapped(path, MAX_TOKEN_BYTES);
        byte[] secret = null;
        String tokenId = null;
        try {
            int i = 0;
            while (i < raw.length) {
                int start = i;
                while (i < raw.length && raw[i] != '\n') {
                    i++;
                }
                int end = i;
                if (i < raw.length && raw[i] == '\n') {
                    i++;
                }
                if (end > start && raw[end - 1] == '\r') {
                    end--;
                }
                if (end == start || raw[start] == '#') {
                    continue;
                }
                int eq = indexOf(raw, start, end, (byte) '=');
                if (eq < 0) {
                    throw new BootstrapException(path + " has a line without '='");
                }
                var key = ascii(raw, start, eq, false);
                if ("token-id".equals(key)) {
                    if (tokenId != null) {
                        throw new BootstrapException(path + " has a duplicate token-id");
                    }
                    tokenId = ascii(raw, eq + 1, end, true);
                    if (!tokenId.matches("[A-Za-z0-9._:-]{1,64}")) {
                        throw new BootstrapException(path + " has an invalid token-id");
                    }
                } else if ("secret".equals(key)) {
                    if (secret != null) {
                        throw new BootstrapException(path + " has a duplicate secret");
                    }
                    secret = copySecret(path, raw, eq + 1, end);
                } else {
                    throw new BootstrapException(path + " has an unknown key");
                }
            }
            if (tokenId == null || secret == null) {
                throw new BootstrapException(path + " requires token-id and secret");
            }
            var material = new JoinMaterial(tokenId, secret, path);
            secret = null;
            return material;
        } catch (BootstrapException e) {
            if (secret != null) {
                Arrays.fill(secret, (byte) 0);
            }
            throw e;
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    static byte[] readCapped(java.nio.file.Path path, int max) throws IOException {
        PosixMode.refuseSymlink(path);
        try (var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size > max) {
                throw new BootstrapException(path + " is too large");
            }
            var buf = ByteBuffer.allocate((int) size);
            while (buf.hasRemaining()) {
                if (channel.read(buf) < 0) {
                    break;
                }
            }
            buf.flip();
            var out = new byte[buf.remaining()];
            buf.get(out);
            return out;
        } catch (BootstrapException e) {
            throw e;
        } catch (java.nio.file.NoSuchFileException e) {
            throw new BootstrapException(path + " does not exist");
        } catch (IOException e) {
            throw new BootstrapException(path + " is not a usable file");
        }
    }

    private static byte[] copySecret(java.nio.file.Path path, byte[] raw, int from, int to) throws IOException {
        int len = to - from;
        if (len < 16 || len > 512) {
            throw new BootstrapException(path + " secret must be 16..512 bytes");
        }
        var secret = new byte[len];
        System.arraycopy(raw, from, secret, 0, len);
        for (byte b : secret) {
            if (b < 0x21 || b > 0x7e) {
                Arrays.fill(secret, (byte) 0);
                throw new BootstrapException(path + " secret must be printable ASCII without spaces");
            }
        }
        return secret;
    }

    private static String ascii(byte[] raw, int from, int to, boolean value) throws IOException {
        if (to < from) {
            throw new BootstrapException("invalid token file");
        }
        for (int i = from; i < to; i++) {
            int b = raw[i] & 0xff;
            if (b < 0x21 || b > 0x7e) {
                throw new BootstrapException(value ? "invalid token file value" : "invalid token file key");
            }
        }
        return new String(raw, from, to - from, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int indexOf(byte[] raw, int from, int to, byte needle) {
        for (int i = from; i < to; i++) {
            if (raw[i] == needle) {
                return i;
            }
        }
        return -1;
    }
}
