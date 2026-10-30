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
package sjws_nettask.clusterharness;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.ServerSocketChannel;

/**
 * Port helpers for tests that must hand a concrete port to a child process or a peer list before anything listens.
 *
 * <p>Opening {@code ServerSocket(0)}, reading the port and closing it (the old {@code freePort()} pattern) is a
 * TOCTOU race: another process can bind that port before the test's server does. Prefer
 * {@link #reserve()} when the listener can take the already-bound socket, or retry the whole startup with a fresh
 * port set when the port must be written into a config first (multi-process HA messaging).
 */
public final class EphemeralPorts {

    /** Bounded retries when a child or in-process bind hits an address already in use. */
    public static final int BIND_ATTEMPTS = 8;

    private EphemeralPorts() {
    }

    /** A free loopback port number; the probe socket is closed before this returns (TOCTOU — prefer {@link #reserve}). */
    public static int probe() throws IOException {
        try (var s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    /** Binds loopback:0 and returns the open channel; the caller owns it and must close it. */
    public static ServerSocketChannel reserve() throws IOException {
        var ch = ServerSocketChannel.open();
        try {
            ch.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
            return ch;
        } catch (IOException e) {
            ch.close();
            throw e;
        }
    }

    /** Binds loopback:0 and returns the open {@link ServerSocket}; the caller owns it. */
    public static ServerSocket reserveSocket() throws IOException {
        var s = new ServerSocket();
        try {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
            return s;
        } catch (IOException e) {
            s.close();
            throw e;
        }
    }

    /** True when {@code t} or its message chain looks like a bind / address-in-use failure. */
    public static boolean addressInUse(Throwable t) {
        for (var c = t; c != null; c = c.getCause()) {
            if (c instanceof BindException) {
                return true;
            }
            if (looksLikeBindMessage(c.getMessage())) {
                return true;
            }
        }
        return false;
    }

    public static boolean looksLikeBindMessage(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        var lower = text.toLowerCase();
        return lower.contains("address already in use")
                || lower.contains("cannot listen on port")
                || lower.contains("failed to start ha messaging")
                || lower.contains("bind exception")
                || (lower.contains("bindexception") && lower.contains("port"));
    }

    /** Logs one bind-retry line to stderr (tests have no shared logger). */
    public static void logRetry(String who, int attempt, Throwable error) {
        var detail = error.getMessage();
        if (detail != null && detail.length() > 200) {
            detail = detail.substring(0, 200) + "…";
        }
        System.err.println(who + ": bind attempt " + attempt + "/" + BIND_ATTEMPTS
                + " failed (address in use); retrying with fresh ports: " + detail);
    }
}
