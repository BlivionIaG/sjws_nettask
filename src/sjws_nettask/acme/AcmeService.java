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
package sjws_nettask.acme;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import sjws_nettask.config.AcmeSettings;
import sjws_nettask.tls.TlsMaterial;

/**
 * Issues and renews on a daemon thread, then publishes the certificate. The request path never
 * waits on this. A delay under a minute after a successful issue is stretched to an hour so a
 * short {@code renew_before} cannot spin. Failures back off from 30 seconds to 15 minutes.
 */
public final class AcmeService implements AutoCloseable {

    private static final Duration MIN_DELAY = Duration.ofMinutes(1);
    private static final Duration SHORT_DELAY = Duration.ofHours(1);
    private static final int BACKOFF_START = 30;
    private static final int BACKOFF_MAX = 15 * 60;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("acme-renew").factory());
    private final AtomicReference<ScheduledFuture<?>> pending = new AtomicReference<>();
    private final ChallengeBoard board;
    private final Consumer<TlsMaterial> publish;
    private final ControlLeadership leadership;
    private final AtomicInteger epoch = new AtomicInteger();
    /** Term whose election NOOP has been applied. 0 until {@link ControlLeadership#leaderReady}. */
    private volatile long readyTerm;
    private volatile AcmeSettings settings;
    private volatile SSLContext trust;
    private volatile int backoffSeconds = BACKOFF_START;
    /** How long {@link #close()} waits for the in-flight attempt to finish its store write. */
    Duration closeWait = Duration.ofSeconds(5);
    /** Test hook. Runs at the start of an attempt that still owns its ticket, before the store is touched. */
    volatile Runnable beforeAttempt;

    public AcmeService(AcmeSettings settings, ChallengeBoard board, Consumer<TlsMaterial> publish) {
        this(settings, board, publish, LocalLeadership.standalone());
    }

    public AcmeService(AcmeSettings settings, ChallengeBoard board, Consumer<TlsMaterial> publish,
            ControlLeadership leadership) {
        this.settings = settings;
        this.board = board;
        this.publish = publish;
        this.leadership = leadership;
    }

    /**
     * Subscribes to leadership. Issuing starts from {@link ControlLeadership#leaderReady}, not
     * from {@link ControlLeadership#isLeader()} alone. A standalone leadership is already ready,
     * so this still arms at once.
     */
    public void start() {
        this.leadership.onLeaderChange(this::leadershipChanged);
        this.leadership.leaderReady(this::leadershipReady);
    }

    public void update(AcmeSettings settings) {
        this.settings = settings;
        this.backoffSeconds = BACKOFF_START;
        if (settings.enabled() && this.readyTerm != 0 && this.leadership.isLeader()) {
            arm(Duration.ZERO);
        } else {
            this.epoch.incrementAndGet();
        }
    }

    private void leadershipChanged(long term, Optional<String> leaderId) {
        if (this.readyTerm == 0) {
            return;
        }
        if (term == this.readyTerm && this.leadership.isLeader()) {
            return;
        }
        this.readyTerm = 0;
        this.epoch.incrementAndGet();
        var queued = this.pending.getAndSet(null);
        if (queued != null) {
            queued.cancel(false);
        }
    }

    private void leadershipReady(long term, long appliedIndex) {
        if (!this.leadership.isLeader() || term <= 0 || appliedIndex < 0) {
            return;
        }
        this.readyTerm = term;
        if (this.settings.enabled()) {
            arm(Duration.ZERO);
        }
    }

    /** Test hook: trust this context for the ACME directory (Pebble). */
    void useTrust(SSLContext ssl) {
        this.trust = ssl;
    }

    /**
     * How long until the certificate should be renewed. Negative when it is already inside the
     * renewal window.
     */
    public static Duration renewalDelay(Instant notAfter, Duration renewBefore, Instant now) {
        return Duration.between(now, notAfter.minus(renewBefore));
    }

    private void arm(Duration delay) {
        if (this.scheduler.isShutdown()) {
            return;
        }
        int ticket = this.epoch.incrementAndGet();
        long millis = Math.max(0, delay.toMillis());
        ScheduledFuture<?> future;
        try {
            future = this.scheduler.schedule(() -> attempt(ticket), millis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            return;
        }
        this.pending.set(future);
        if (this.scheduler.isShutdown()) {
            future.cancel(false);
        }
    }

    private void attempt(int ticket) {
        if (ticket != this.epoch.get() || !this.settings.enabled()) {
            return;
        }
        try {
            var hook = this.beforeAttempt;
            if (hook != null) {
                hook.run();
            }
            var store = new AcmeStore(this.settings.storage());
            var existing = store.loadCertificate();
            if (existing != null) {
                var notAfter = existing.chain()[0].getNotAfter().toInstant();
                if (notAfter.isAfter(Instant.now())) {
                    this.publish.accept(existing);
                    var delay = renewalDelay(notAfter, this.settings.renewBefore(), Instant.now());
                    if (delay.compareTo(MIN_DELAY) >= 0) {
                        this.backoffSeconds = BACKOFF_START;
                        if (ticket == this.epoch.get()) {
                            arm(delay);
                        }
                        return;
                    }
                }
            }
            var issued = AcmeIssuer.issue(this.settings, this.board, this.trust);
            this.publish.accept(issued);
            this.backoffSeconds = BACKOFF_START;
            var delay = renewalDelay(issued.chain()[0].getNotAfter().toInstant(), this.settings.renewBefore(),
                    Instant.now());
            if (delay.compareTo(MIN_DELAY) < 0) {
                delay = SHORT_DELAY;
            }
            if (ticket == this.epoch.get()) {
                arm(delay);
            }
        } catch (Exception e) {
            System.err.println("ERROR: ACME: " + AcmeIssuer.safe(e));
            int wait = this.backoffSeconds;
            this.backoffSeconds = Math.min(wait * 2, BACKOFF_MAX);
            if (ticket == this.epoch.get()) {
                arm(Duration.ofSeconds(wait));
            }
        }
    }

    /**
     * Stops the renew loop without interrupting an attempt that is already writing the store.
     * A queued delay is cancelled. {@link #close()} waits up to {@link #closeWait} for the
     * in-flight attempt to finish, then interrupts it and logs if it is still running.
     */
    @Override
    public void close() {
        this.epoch.incrementAndGet();
        var queued = this.pending.getAndSet(null);
        if (queued != null) {
            queued.cancel(false);
        }
        this.scheduler.shutdown();
        try {
            if (!this.scheduler.awaitTermination(Math.max(1, this.closeWait.toMillis()), TimeUnit.MILLISECONDS)) {
                System.err.println(
                        "WARN: ACME renew thread did not stop within " + this.closeWait.toMillis() + " ms");
                this.scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("WARN: interrupted while waiting for the ACME renew thread");
            this.scheduler.shutdownNow();
        }
        this.board.clear();
    }
}
