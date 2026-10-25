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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Response;
import sjws_nettask.messaging.ledger.LeaderUnavailableException;

/** #246 (c): a failed replicated write or leader read never sends internal error text to the client. */
class MessagingHttpFailedTest {

    private static final String SECRET = "/var/lib/sjws/msg_p0.log: group msg/p0 failed closed, IOException: EIO";

    private static String body(Response r) {
        return new String(MessagingHttpTest.bytes(r), StandardCharsets.UTF_8);
    }

    private static ExecutionException internal() {
        return new ExecutionException(new CompletionException(new UncheckedIOException(SECRET, new IOException(SECRET))));
    }

    @Test
    void failedWriteIsAGenericOutcomeUnknownProblem() {
        var r = MessagingHttp.failed(internal(), "/messaging/v1/topics/t/records", true);
        assertEquals(503, r.status());
        assertEquals("application/problem+json", r.headers().get("content-type"));
        assertEquals(MessagingHttp.RETRY_AFTER_SECONDS, r.headers().get("retry-after"));
        var json = body(r);
        assertTrue(json.contains("\"type\":\"" + MessagingHttp.OUTCOME_UNKNOWN_TYPE + "\""), json);
        assertTrue(json.contains("\"title\":\"outcome unknown\""), json);
        assertFalse(json.contains("msg_p0") || json.contains("IOException") || json.contains("/var/lib"), json);
    }

    @Test
    void failedLeaderReadIsPlainUnavailableWithoutInternals() {
        var r = MessagingHttp.failed(internal(), "/messaging/v1/topics/t/records", false);
        assertEquals(503, r.status());
        var json = body(r);
        assertFalse(json.contains(MessagingHttp.OUTCOME_UNKNOWN_TYPE), json);
        assertTrue(json.contains("\"title\":\"unavailable\""), json);
        assertFalse(json.contains("msg_p0") || json.contains("IOException") || json.contains("/var/lib"), json);
    }

    @Test
    void logIoErrorIsAGeneric500() {
        var r = MessagingHttp.logError(new IOException(SECRET), "/messaging/v1/topics/t/records");
        assertEquals(500, r.status());
        var json = body(r);
        assertTrue(json.contains("\"type\":\"about:blank\"") && json.contains("\"title\":\"log error\""), json);
        assertFalse(json.contains("msg_p0") || json.contains("IOException") || json.contains("/var/lib"), json);
    }

    @Test
    void unavailableLogIsAGeneric503WithRetryAfter() {
        var r = MessagingHttp.unavailable(new IllegalStateException("topic " + SECRET + " is not replicated"),
                "/messaging/v1/topics/t/records");
        assertEquals(503, r.status());
        assertEquals(MessagingHttp.RETRY_AFTER_SECONDS, r.headers().get("retry-after"));
        var json = body(r);
        assertTrue(json.contains("\"title\":\"unavailable\""), json);
        assertFalse(json.contains("msg_p0") || json.contains("replicated") || json.contains("/var/lib"), json);
    }

    @Test
    void leaderLossWithAKnownLeaderStillRedirects() {
        var e = new ExecutionException(new CompletionException(
                new LeaderUnavailableException("not leader", 9, "n2", "http://n2:8080/")));
        var r = MessagingHttp.failed(e, "/messaging/v1/topics/t/records", true);
        assertEquals(307, r.status());
        assertEquals("http://n2:8080/messaging/v1/topics/t/records", r.headers().get("location"));
        assertEquals("9", r.headers().get("sjws-term"));
        assertTrue(body(r).contains(MessagingHttp.NOT_LEADER_TYPE));
    }

    /** #254: an accepted write with an unknown fate keeps the leader hint but is a typed 503, never a 307. */
    @Test
    void outcomeUnknownWithAKnownLeaderIsATyped503NotARedirect() {
        var e = new ExecutionException(new CompletionException(new sjws_nettask.messaging.ledger.WriteOutcomeUnknownException(
                SECRET, 9, "n2", "http://n2:8080/", new UncheckedIOException(SECRET, new IOException(SECRET)))));
        var r = MessagingHttp.failed(e, "/messaging/v1/topics/t/records", true);
        assertEquals(503, r.status());
        assertEquals(null, r.headers().get("location"));
        assertEquals(MessagingHttp.RETRY_AFTER_SECONDS, r.headers().get("retry-after"));
        assertEquals("9", r.headers().get("sjws-term"));
        assertEquals("n2", r.headers().get("sjws-leader"));
        var json = body(r);
        assertTrue(json.contains("\"type\":\"" + MessagingHttp.OUTCOME_UNKNOWN_TYPE + "\""), json);
        assertTrue(json.contains("\"leaderUrl\":\"http://n2:8080/messaging/v1/topics/t/records\""), json);
        assertFalse(json.contains("msg_p0") || json.contains("IOException") || json.contains("/var/lib"), json);
    }

    /** #254: per-failure logs carry a full trace once per interval per exception type, one line otherwise. */
    @Test
    void failureLogsRateLimitStackTracesPerType() {
        long[] now = {0};
        var log = new ThrottledLog(System.getLogger("test.throttled"), java.time.Duration.ofSeconds(30), () -> now[0]);
        assertTrue(log.claim(IOException.class, now[0]), "first trace of a type is logged in full");
        assertFalse(log.claim(IOException.class, now[0] + 1), "then one-line");
        assertTrue(log.claim(IllegalStateException.class, now[0] + 1), "per type");
        now[0] += java.time.Duration.ofSeconds(30).toNanos();
        assertTrue(log.claim(IOException.class, now[0]), "a full trace again after the interval");
        for (int i = 0; i < ThrottledLog.MAX_TYPES + 5; i++) log.claim(Class.class, now[0]); // same key: no growth
        log.log(System.Logger.Level.DEBUG, "smoke", new IOException("x"));
    }

    @Test
    void leaderLossStillMapsToNotLeader() {
        var e = new ExecutionException(new LeaderUnavailableException("not leader", 7, "n2", null));
        var r = MessagingHttp.failed(e, "/messaging/v1/topics/t/records", true);
        assertEquals(503, r.status());
        assertTrue(body(r).contains(MessagingHttp.NOT_LEADER_TYPE));
        assertEquals("7", r.headers().get("sjws-term"));
    }
}
