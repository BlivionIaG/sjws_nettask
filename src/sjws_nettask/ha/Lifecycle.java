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

import java.util.concurrent.locks.ReentrantLock;

/**
 * Graceful drain. Not-ready, then the deregistration delay, then {@code Connection: close},
 * then stop accepting and wait for in-flight work. Signal handling stays outside this class.
 */
public final class Lifecycle {

    private final HealthBoard health;
    private final HaSettings settings;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean closeAfterResponse;
    private boolean started;
    private boolean finished;

    public Lifecycle(HealthBoard health, HaSettings settings) {
        this.health = health;
        this.settings = settings;
    }

    /** Read once per keep-alive response when HA is attached. False until drain passes the delay. */
    public boolean closeAfterResponse() {
        return this.closeAfterResponse;
    }

    public void beginClosingConnections() {
        this.closeAfterResponse = true;
    }

    /**
     * @param stopAccepting expire idle connections, refuse new ones, close the listen socket
     * @param awaitAcceptLoop block until the accept loop has finished in-flight work or timed out
     */
    public void drain(Runnable stopAccepting, Runnable awaitAcceptLoop) throws InterruptedException {
        this.lock.lock();
        try {
            if (this.finished) {
                return;
            }
            if (this.started) {
                while (!this.finished) {
                    this.lock.unlock();
                    try {
                        Thread.sleep(5);
                    } finally {
                        this.lock.lock();
                    }
                }
                return;
            }
            this.started = true;
        } finally {
            this.lock.unlock();
        }

        try {
            this.health.setDraining(true);
            this.settings.events().drainStarted();
            this.settings.metrics().counter("ha_drains_total", 1);
            this.settings.metrics().gauge("ha_ready", 0);
            this.settings.sleeper().sleep(this.settings.drainDelay());
            beginClosingConnections();
            stopAccepting.run();
            awaitAcceptLoop.run();
        } finally {
            this.settings.events().drainFinished();
            this.lock.lock();
            try {
                this.finished = true;
            } finally {
                this.lock.unlock();
            }
        }
    }
}
