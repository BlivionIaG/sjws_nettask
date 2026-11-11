/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package sjws_nettask.messaging.http;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.core.Body;
import sjws_nettask.core.Handler;
import sjws_nettask.core.HandlerMounts;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.messaging.ledger.Durability;

/** NONE hooks skip nanoTime; late hooks() updates the handler (#400 Codex P1). */
final class MessagingHooksNonePathTest {

    @TempDir
    Path dir;

    @Test
    void noneHooksSkipTimingAndLateInstallUpdatesHttp() throws Exception {
        try (var topics = new LogTopics(dir, Durability.ASYNC, 1 << 20, LogRetention.NONE, System::currentTimeMillis)) {
            var http = new MessagingHttp(topics);
            assertTrue(MessagingHttpTest.post(http, "t", MessagingHttpTest.recs("a"), null).status() >= 200);
            assertTrue(http.handle(new Request("GET", "/messaging/v1/topics/t/records")).status() >= 200);

            var counted = new AtomicInteger();
            http.hooks(new MessagingHooks() {
                @Override
                public void produced(String topic, int status, long bytes, long nanos) {
                    counted.incrementAndGet();
                }

                @Override
                public void fetched(String topic, int status, long bytes, long nanos) {
                    counted.incrementAndGet();
                }
            });
            assertTrue(MessagingHttpTest.post(http, "t", MessagingHttpTest.recs("b"), null).status() >= 200);
            assertTrue(http.handle(new Request("GET", "/messaging/v1/topics/t/records")).status() >= 200);
            assertTrue(counted.get() >= 2, "late hooks() must observe produce and fetch");
        }
    }

    @Test
    void moduleLateHooksUpdateMountedHttp() throws Exception {
        var module = new MessagingModule(dir);
        try {
            Handler fallback = r -> new Response(418, ResponseHeaders.of(), Body.Bytes.of(new byte[0]));
            var h = HandlerMounts.install(List.of(module), fallback);
            var req = new Request("POST", "/messaging/v1/topics/late/records", RequestHeaders.of(Map.of()))
                    .withBody(MessagingHttpTest.body(MessagingHttpTest.recs("x")));
            assertTrue(h.handle(req).status() >= 200);

            var counted = new AtomicInteger();
            module.hooks(new MessagingHooks() {
                @Override
                public void produced(String topic, int status, long bytes, long nanos) {
                    counted.incrementAndGet();
                }

                @Override
                public void fetched(String topic, int status, long bytes, long nanos) {
                }
            });
            var req2 = new Request("POST", "/messaging/v1/topics/late/records", RequestHeaders.of(Map.of()))
                    .withBody(MessagingHttpTest.body(MessagingHttpTest.recs("y")));
            assertTrue(h.handle(req2).status() >= 200);
            assertTrue(counted.get() >= 1, "MessagingModule.hooks must propagate to mounted MessagingHttp");
        } finally {
            module.close();
        }
    }
}
