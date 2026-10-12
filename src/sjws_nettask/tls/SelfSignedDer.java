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
package sjws_nettask.tls;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Minimal DER encoder for one self-signed X.509 v3 certificate (ecdsa-with-SHA256). */
final class SelfSignedDer {

    private static final byte[] ECDSA_SHA256 = {0x06, 0x08, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x04, 0x03, 0x02};
    private static final byte[] COMMON_NAME = {0x06, 0x03, 0x55, 0x04, 0x03};
    private static final byte[] SUBJECT_ALT_NAME = {0x06, 0x03, 0x55, 0x1D, 0x11};
    private static final DateTimeFormatter UTC_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private SelfSignedDer() {
    }

    static byte[] tbsCertificate(String fqdn, byte[] subjectPublicKeyInfo, SecureRandom random, long notBefore,
            long notAfter) {
        var serial = new BigInteger(127, random).setBit(126); // positive, 16 bytes
        var name = seq(set(seq(COMMON_NAME, tlv(0x0C, fqdn.getBytes(StandardCharsets.UTF_8)))));
        var sans = new ByteArrayOutputStream();
        sans.writeBytes(tlv(0x82, fqdn.getBytes(StandardCharsets.US_ASCII))); // dNSName
        if (!"localhost".equals(fqdn)) {
            sans.writeBytes(tlv(0x82, "localhost".getBytes(StandardCharsets.US_ASCII)));
        }
        sans.writeBytes(tlv(0x87, ip("127.0.0.1"))); // iPAddress
        sans.writeBytes(tlv(0x87, ip("::1")));
        var extensions = tlv(0xA3, seq(seq(SUBJECT_ALT_NAME, tlv(0x04, seq(sans.toByteArray())))));
        return seq(
                tlv(0xA0, tlv(0x02, new byte[] {2})), // v3
                tlv(0x02, serial.toByteArray()),
                seq(ECDSA_SHA256),
                name,
                seq(time(notBefore), time(notAfter)),
                name,
                subjectPublicKeyInfo,
                extensions);
    }

    static byte[] certificate(byte[] tbs, byte[] signature) {
        var bits = new byte[signature.length + 1]; // leading "0 unused bits"
        System.arraycopy(signature, 0, bits, 1, signature.length);
        return seq(tbs, seq(ECDSA_SHA256), tlv(0x03, bits));
    }

    private static byte[] time(long millis) {
        return tlv(0x17, UTC_TIME.format(Instant.ofEpochMilli(millis)).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] ip(String literal) {
        try {
            return InetAddress.getByName(literal).getAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static byte[] seq(byte[]... parts) {
        return tlv(0x30, parts);
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, parts);
    }

    static byte[] tlv(int tag, byte[]... parts) {
        var content = new ByteArrayOutputStream();
        for (var p : parts) {
            content.writeBytes(p);
        }
        var out = new ByteArrayOutputStream();
        out.write(tag);
        int length = content.size();
        if (length < 0x80) {
            out.write(length);
        } else if (length < 0x100) {
            out.write(0x81);
            out.write(length);
        } else if (length < 0x10000) {
            out.write(0x82);
            out.write(length >> 8);
            out.write(length);
        } else {
            out.write(0x83);
            out.write(length >> 16);
            out.write(length >> 8);
            out.write(length);
        }
        out.writeBytes(content.toByteArray());
        return out.toByteArray();
    }
}
