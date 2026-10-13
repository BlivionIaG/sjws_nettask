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
package sjws_nettask.ha;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** In-process bundle board. The bytes on the wire are HMAC-SHA256 then the payload. */
public final class HmacBundles {

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Long, byte[]> signed = new HashMap<>();

    public void publish(long generation, byte[] payload, byte[] secret) {
        var mac = mac(payload, secret);
        var body = new byte[mac.length + payload.length];
        System.arraycopy(mac, 0, body, 0, mac.length);
        System.arraycopy(payload, 0, body, mac.length, payload.length);
        lock.lock();
        try {
            signed.put(generation, body);
        } finally {
            lock.unlock();
        }
    }

    public byte[] pull(long generation, byte[] secret) throws IOException {
        byte[] body;
        lock.lock();
        try {
            body = signed.get(generation);
            if (body != null) {
                body = body.clone();
            }
        } finally {
            lock.unlock();
        }
        if (body == null) {
            throw new IOException("missing generation " + generation);
        }
        if (body.length < 32) {
            throw new IOException("short bundle");
        }
        var payload = Arrays.copyOfRange(body, 32, body.length);
        if (!Arrays.equals(mac(payload, secret), Arrays.copyOf(body, 32))) {
            throw new IOException("hmac");
        }
        return payload;
    }

    static byte[] mac(byte[] payload, byte[] secret) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException failed) {
            throw new IllegalStateException(failed);
        }
    }
}
