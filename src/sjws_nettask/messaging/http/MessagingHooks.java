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
package sjws_nettask.messaging.http;

/**
 * Observability hook points for {@code /messaging/v1} (docs/design/partition-log.md §2.4). The metrics are
 * {@code sjws_messaging_produce_duration_seconds} and {@code sjws_messaging_fetch_duration_seconds} (labels
 * {@code topic}, {@code status}); the exporter that defines them implements this interface. Called on the request
 * thread after the response is built, so implementations must be cheap and must not throw.
 */
public interface MessagingHooks {

    /** No-op; the default. */
    MessagingHooks NONE = new MessagingHooks() {
        @Override public void produced(String topic, int status, long bytes, long nanos) { }
        @Override public void fetched(String topic, int status, long bytes, long nanos) { }
    };

    /** One {@code POST}: response status, request body bytes, handler time. */
    void produced(String topic, int status, long bytes, long nanos);

    /** One {@code GET}: response status, response body bytes, handler time (excludes sending the body). */
    void fetched(String topic, int status, long bytes, long nanos);
}
