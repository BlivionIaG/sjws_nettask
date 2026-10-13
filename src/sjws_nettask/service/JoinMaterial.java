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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Join token held as bytes, not a {@link String}. {@link #copySecret()} returns a clone; the
 * caller wipes that clone with {@link Arrays#fill} after the control plane has seen it.
 * {@link #toString()} is the token id only.
 */
public final class JoinMaterial {

    private final String tokenId;
    private final Path path;
    private final ReentrantLock lock = new ReentrantLock();
    private byte[] secret;

    JoinMaterial(String tokenId, byte[] secret, Path path) {
        this.tokenId = tokenId;
        this.secret = secret;
        this.path = path;
    }

    public String tokenId() {
        return this.tokenId;
    }

    public Path path() {
        return this.path;
    }

    /** A clone of the secret, or an exception once it has been wiped. The clone is the caller's to wipe. */
    public byte[] copySecret() {
        this.lock.lock();
        try {
            if (this.secret == null) {
                throw new IllegalStateException("join token is gone");
            }
            return this.secret.clone();
        } finally {
            this.lock.unlock();
        }
    }

    public boolean discarded() {
        this.lock.lock();
        try {
            return this.secret == null;
        } finally {
            this.lock.unlock();
        }
    }

    /** Zeros the in-memory secret and leaves the file for a later start. */
    void wipeMemory() {
        this.lock.lock();
        try {
            zero();
        } finally {
            this.lock.unlock();
        }
    }

    /** Zeros the secret and deletes the token file. Idempotent when the file is already gone. */
    void destroy() throws IOException {
        this.lock.lock();
        try {
            zero();
            Files.deleteIfExists(this.path);
        } finally {
            this.lock.unlock();
        }
    }

    /** True when the buffer is gone or every byte is zero. Tests use this; it does not reveal the secret. */
    boolean memoryCleared() {
        this.lock.lock();
        try {
            if (this.secret == null) {
                return true;
            }
            for (byte b : this.secret) {
                if (b != 0) {
                    return false;
                }
            }
            return true;
        } finally {
            this.lock.unlock();
        }
    }

    private void zero() {
        if (this.secret != null) {
            Arrays.fill(this.secret, (byte) 0);
            this.secret = null;
        }
    }

    @Override
    public String toString() {
        return "JoinMaterial[" + this.tokenId + "]";
    }
}
