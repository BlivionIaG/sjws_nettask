/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package sjws_nettask.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BindRetryTest {

    @Test
    void backoffDoublesUpToTheCapAndResetsOnSuccess() {
        var retry = new BindRetry();
        long now = 1_000L;
        assertTrue(retry.due(now));
        assertTrue(retry.failed(now, "busy"));
        assertEquals(BindRetry.MIN_NANOS, retry.delayNanos());
        assertFalse(retry.due(now + BindRetry.MIN_NANOS - 1));
        assertTrue(retry.due(now + BindRetry.MIN_NANOS));
        assertFalse(retry.failed(now, "busy"), "the same failure is logged once");
        assertEquals(2 * BindRetry.MIN_NANOS, retry.delayNanos());
        for (int i = 0; i < 20; i++) {
            retry.failed(now, "busy");
        }
        assertEquals(BindRetry.MAX_NANOS, retry.delayNanos());
        assertFalse(retry.due(now + BindRetry.MAX_NANOS - 1));
        assertTrue(retry.failed(now, "other"), "a different failure is logged");
        retry.succeeded();
        assertEquals(0L, retry.delayNanos());
        assertTrue(retry.due(now));
        assertTrue(retry.failed(now, "other"), "after a success the next failure is logged again");
    }
}
