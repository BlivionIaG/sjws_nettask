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
package sjws_nettask.routing;

import java.io.IOException;
import java.util.Objects;
import sjws_nettask.core.Body;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;

/**
 * Per-route retry / circuit override (design §13). Wraps a shared {@link ProxyHandler},
 * {@link EndpointBalancer} or {@link WeightedHandler} so the connection pool stays shared while
 * the retry budget and circuit breaker are private to this route. One-shot calls skip the inner
 * handler's own resilience, so retries are not nested.
 */
public final class RouteResilience implements Handler {

    private final Handler inner;
    private final Resilience.Retry retry;
    private final Resilience.RetryBudget budget;
    private final Resilience.CircuitBreaker breaker;

    public RouteResilience(Handler inner, Resilience policy) {
        this.inner = Objects.requireNonNull(inner, "inner");
        Objects.requireNonNull(policy, "policy");
        this.retry = policy.retry();
        this.budget = this.retry == null ? null : new Resilience.RetryBudget(this.retry.budgetPercent());
        this.breaker = policy.circuit() == null ? null : new Resilience.CircuitBreaker(policy.circuit());
    }

    @Override
    public Response handle(Request request) {
        boolean head = request.headOnly();
        if (this.breaker != null && !this.breaker.allow()) {
            var open = StaticFileHandler.error(503, head);
            open.headers().add("retry-after", Long.toString(this.breaker.retryAfterSeconds()));
            return open;
        }
        var retry = this.retry;
        boolean mayRetry = retry != null && request.body().length() == 0 && ProxyHandler.idempotent(request.method());
        if (retry != null) {
            this.budget.deposit();
        }
        int cap = Math.max(1, (retry == null ? 0 : retry.attempts()) + 1);
        // A backends: route keeps per-backend history; a single balancer keeps one endpoint list.
        var weightedState = this.inner instanceof WeightedHandler weighted ? weighted.attempts(cap) : null;
        String[] tried = weightedState == null ? new String[cap] : null;
        int[] nTried = weightedState == null ? new int[1] : null;
        int tries = 0;
        while (true) {
            Resilience.On failed;
            Response response = null;
            int errorStatus = 0;
            try {
                response = oneShot(request, tried, nTried, weightedState);
                if (ProxyHandler.isLocalRefusal(response)) {
                    if (this.breaker != null) {
                        this.breaker.release();
                    }
                    return response;
                }
                failed = ProxyHandler.kind(response);
            } catch (IOException e) {
                failed = ProxyHandler.kind(e);
                errorStatus = ProxyHandler.status(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (this.breaker != null) {
                    this.breaker.release();
                }
                return StaticFileHandler.error(502, head);
            }
            boolean again = failed != null && mayRetry && tries < retry.attempts() && retry.on().contains(failed)
                    && this.budget.withdraw();
            if (!again) {
                if (this.breaker != null) {
                    if (failed == null) {
                        this.breaker.success();
                    } else {
                        this.breaker.failure();
                    }
                }
                return response != null ? response : StaticFileHandler.error(errorStatus, head);
            }
            if (response != null) {
                Body.discard(response.body());
            }
            tries++;
        }
    }

    public Handler inner() {
        return this.inner;
    }

    String circuitState() {
        return this.breaker == null ? "NONE" : this.breaker.state().name();
    }

    private Response oneShot(Request request, String[] tried, int[] nTried, WeightedHandler.Attempts weightedState)
            throws IOException, InterruptedException {
        if (this.inner instanceof ProxyHandler proxy) {
            return proxy.attemptWithPending(request);
        }
        if (this.inner instanceof EndpointBalancer balancer) {
            return balancer.oneShot(request, tried, nTried);
        }
        if (this.inner instanceof WeightedHandler weighted) {
            return weighted.oneShot(request, weightedState);
        }
        return this.inner.handle(request);
    }
}
