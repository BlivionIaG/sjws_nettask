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

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.nio.channels.ServerSocketChannel;

/**
 * {@code SO_REUSEPORT} for a rolling binary hand-over. The JDK has no socket option for it, so
 * this uses the channel fd and {@code setsockopt}. Returns false when the nio packages are not
 * opened or the call fails; the caller then drains and binds after the old process exits.
 */
public final class ReusePort {

    static final int SOL_SOCKET = 1;
    static final int SO_REUSEPORT = 15;

    private ReusePort() {
    }

    public static boolean enable(ServerSocketChannel channel) {
        if (channel == null) {
            return false;
        }
        try {
            int fd = fileDescriptor(channel);
            return fd >= 0 && setsockopt(fd);
        } catch (Throwable failed) {
            return false;
        }
    }

    private static int fileDescriptor(ServerSocketChannel channel) throws ReflectiveOperationException {
        var fdField = channel.getClass().getDeclaredField("fd");
        fdField.setAccessible(true);
        var fd = fdField.get(channel);
        if (fd == null) {
            return -1;
        }
        var raw = fd.getClass().getDeclaredField("fd");
        raw.setAccessible(true);
        return raw.getInt(fd);
    }

    static boolean setsockopt(int fd) {
        if (fd < 0) {
            return false;
        }
        try {
            var linker = Linker.nativeLinker();
            var symbol = linker.defaultLookup().find("setsockopt");
            if (symbol.isEmpty()) {
                return false;
            }
            var handle = linker.downcallHandle(symbol.get(), FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT));
            try (var arena = Arena.ofConfined()) {
                var opt = arena.allocate(ValueLayout.JAVA_INT);
                opt.set(ValueLayout.JAVA_INT, 0, 1);
                int rc = (int) handle.invoke(fd, SOL_SOCKET, SO_REUSEPORT, opt, Integer.BYTES);
                return rc == 0;
            }
        } catch (Throwable failed) {
            return false;
        }
    }
}
