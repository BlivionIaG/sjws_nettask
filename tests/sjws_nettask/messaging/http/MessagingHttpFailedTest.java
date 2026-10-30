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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Response;
import sjws_nettask.observe.AccessTargetMask;
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

    /**
     * #254: the two 503s a write can get must not be confused. not-appended: definitely not applied, safe to retry.
     * outcome-unknown: may or may not be applied. Both carry Retry-After; type, title and detail differ.
     */
    @Test
    void notAppendedAndOutcomeUnknownCannotBeConfused() {
        var target = "/messaging/v1/topics/t/records";
        var notAppended = MessagingHttp.failed(new ExecutionException(
                new sjws_nettask.messaging.ledger.WriteRefusedException("lag", true, null)), target, true);
        var unknown = MessagingHttp.failed(new ExecutionException(
                new sjws_nettask.messaging.ledger.WriteOutcomeUnknownException("lost", 3, null, null, null)), target, true);
        assertEquals(503, notAppended.status());
        assertEquals(503, unknown.status());
        assertEquals(MessagingHttp.RETRY_AFTER_SECONDS, notAppended.headers().get("retry-after"));
        assertEquals(MessagingHttp.RETRY_AFTER_SECONDS, unknown.headers().get("retry-after"));
        var a = body(notAppended);
        var u = body(unknown);
        assertTrue(a.contains("\"type\":\"" + MessagingHttp.NOT_APPENDED_TYPE + "\""), a);
        assertTrue(a.contains("\"title\":\"not appended\"") && a.contains("nothing was appended"), a);
        assertFalse(a.contains(MessagingHttp.OUTCOME_UNKNOWN_TYPE), a);
        assertTrue(u.contains("\"type\":\"" + MessagingHttp.OUTCOME_UNKNOWN_TYPE + "\""), u);
        assertTrue(u.contains("\"title\":\"outcome unknown\"") && u.contains("may or may not"), u);
        assertFalse(u.contains(MessagingHttp.NOT_APPENDED_TYPE) || u.contains("nothing was appended"), u);
        assertFalse(MessagingHttp.NOT_APPENDED_TYPE.equals(MessagingHttp.OUTCOME_UNKNOWN_TYPE));
    }

    /**
     * Messaging Core C2: a leader read (GET) that fails on a fenced group, or with any refusal, is a plain 503
     * unavailable: never not-appended or outcome-unknown, which describe writes.
     */
    @Test
    void aFailedLeaderReadIsNeverAWriteOutcome() {
        var target = "/messaging/v1/topics/t/records";
        var fenced = sjws_nettask.cluster.ClusterErrors.fenced(new sjws_nettask.cluster.GroupId("msg/t"));
        for (Throwable c : List.<Throwable>of(fenced,
                new sjws_nettask.messaging.ledger.WriteRefusedException("lag", true, null),
                new sjws_nettask.messaging.ledger.WriteOutcomeUnknownException("lost", 3, "n2", "http://n2", null))) {
            var r = MessagingHttp.failed(new ExecutionException(new CompletionException(c)), target, false);
            var json = body(r);
            assertEquals(503, r.status(), json);
            assertTrue(json.contains("\"title\":\"unavailable\""), json);
            assertFalse(json.contains(MessagingHttp.NOT_APPENDED_TYPE) || json.contains(MessagingHttp.OUTCOME_UNKNOWN_TYPE), json);
            assertEquals(null, r.headers().get("location"));
        }
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

    /**
     * MessagingHttp ERROR/WARN paths must mask pinned credential query values the same way as the
     * access log, so a failure line cannot leak an api-key query param.
     */
    @Test
    void errorAndWarnLogTargetsUsePinnedMask() {
        var secret = "S2-MSG-DUMMY-KEY";
        var target = "/messaging/v1/topics/t/records?api_key=" + secret + "&x=1";
        var records = new ArrayList<LogRecord>();
        var jul = Logger.getLogger(MessagingHttp.class.getName());
        var handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        var previous = jul.getLevel();
        jul.addHandler(handler);
        jul.setLevel(Level.ALL);
        AccessTargetMask.pin(new String[] {"api_key"});
        try {
            MessagingHttp.logError(new IOException("disk"), target);
            MessagingHttp.unavailable(new IllegalStateException("closed"), target);
            MessagingHttp.failed(internal(), target, true);
        } finally {
            AccessTargetMask.clearPinned();
            jul.removeHandler(handler);
            jul.setLevel(previous);
        }
        assertFalse(records.isEmpty(), "expected JUL records from MessagingHttp logger");
        for (var record : records) {
            var msg = record.getMessage();
            assertNotNull(msg);
            assertFalse(msg.contains(secret), msg);
            assertTrue(msg.contains("api_key=***") || !msg.contains("api_key="), msg);
        }
        assertTrue(records.stream().anyMatch(r -> r.getMessage().contains("api_key=***")),
                records.stream().map(LogRecord::getMessage).toList().toString());
    }

}
