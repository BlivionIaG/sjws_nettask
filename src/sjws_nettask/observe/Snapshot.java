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

/**
 * Point-in-time view of {@link ServerMetrics} for an admin TUI. The arrays are copies: reading them
 * does not race the counters, and changing them does not change the server.
 *
 * @param requests responses recorded so far
 * @param bytesSent response body bytes (HEAD, 1xx, 204 and 304 contribute 0; a write that threw contributes 0)
 * @param activeConnections HTTP/1.1 connections being served plus established HTTP/3 connections
 * @param http1Connections {@link sjws_nettask.ClientManager#activeConnections()}
 * @param http3Connections established QUIC connections, or 0 when HTTP/3 is not running
 * @param accessLogDropped lines not written because the buffer was full or the stream failed
 * @param requestsByStatus index is the status code (0 outside 100..999)
 * @param protocols counts for HTTP/1.0, HTTP/1.1, HTTP/2, HTTP/3 and anything else, in that order
 * @param latency non-cumulative histogram of request time, including the response write
 */
public record Snapshot(long requests, long bytesSent, int activeConnections, int http1Connections, int http3Connections,
        long accessLogDropped, long[] requestsByStatus, long[] protocols, Latency latency) {

    public Snapshot {
        requestsByStatus = requestsByStatus.clone();
        protocols = protocols.clone();
    }

    /** @return responses with this status code, or 0 when {@code status} is out of range */
    public long status(int status) {
        return status >= 0 && status < this.requestsByStatus.length ? this.requestsByStatus[status] : 0;
    }

    /**
     * @param name {@code HTTP/1.0}, {@code HTTP/1.1}, {@code HTTP/2}, {@code HTTP/3}, or anything else
     *        (counted as other)
     */
    public long protocol(String name) {
        int id = RequestObserver.protocolId(name);
        return id >= 0 && id < this.protocols.length ? this.protocols[id] : 0;
    }

    /**
     * @param upperBoundNanos inclusive upper bound of each bucket; the last one is {@link Long#MAX_VALUE}
     * @param counts responses in that bucket only (not cumulative)
     */
    public record Latency(long count, long sumNanos, long maxNanos, long[] upperBoundNanos, long[] counts) {

        public Latency {
            upperBoundNanos = upperBoundNanos.clone();
            counts = counts.clone();
        }

        /** @return the mean, or 0 when nothing has been recorded */
        public double meanNanos() {
            return this.count == 0 ? 0 : (double) this.sumNanos / this.count;
        }

        /** @return responses at or under {@code upperBoundNanos[bucket]}, Prometheus-style */
        public long cumulative(int bucket) {
            long n = 0;
            int last = Math.min(bucket, this.counts.length - 1);
            for (int i = 0; i <= last; i++) {
                n += this.counts[i];
            }
            return n;
        }
    }
}
