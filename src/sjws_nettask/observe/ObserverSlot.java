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

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import sjws_nettask.ServerConfig;

/**
 * The observer both transports read on each request. It starts as {@link RequestObserver#NONE}
 * unless logging, {@code /metrics} or {@code /healthz} was configured. {@link #arm(ServerConfig)}
 * is what an admin snapshot uses to install counters later; the next request sees it.
 */
public final class ObserverSlot {

    private static final VarHandle CURRENT;

    static {
        try {
            CURRENT = MethodHandles.lookup().findVarHandle(ObserverSlot.class, "current", RequestObserver.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private volatile RequestObserver current;

    public ObserverSlot(RequestObserver initial) {
        this.current = initial == null ? RequestObserver.NONE : initial;
    }

    /** The observer for this request. {@link RequestObserver#NONE} means the hook is off. */
    public RequestObserver get() {
        return this.current;
    }

    /** Replaces the observer. Used before the listeners accept. */
    public void set(RequestObserver observer) {
        this.current = observer == null ? RequestObserver.NONE : observer;
    }

    /**
     * Installs a recording observer when the slot is still {@link RequestObserver#NONE}.
     * A slot that already records is left as it is, so a later snapshot does not reopen the access log.
     *
     * @return the observer now installed
     */
    public RequestObserver arm(ServerConfig config) throws IOException {
        if (this.current != RequestObserver.NONE) {
            return this.current;
        }
        return replace(RequestObserver.open(config));
    }

    /**
     * Publishes {@code opened} if the slot is still {@link RequestObserver#NONE}. Otherwise closes
     * {@code opened} and keeps the observer that won.
     */
    RequestObserver replace(RequestObserver opened) {
        if (CURRENT.compareAndSet(this, RequestObserver.NONE, opened)) {
            return opened;
        }
        opened.close();
        return this.current;
    }

    public void close() {
        this.current.close();
    }
}
