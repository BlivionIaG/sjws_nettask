/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package sjws_nettask.h2;

/**
 * Test-only callbacks around HTTP/2 dispatch. Production uses {@link #NONE} exclusively via a
 * separate handler subclass ({@link HookedHttp2StreamHandler}); the stock
 * {@link Http2StreamHandler} hot path never mentions hooks.
 */
interface DispatchHooks {

    DispatchHooks NONE = new DispatchHooks() {
    };

    default void afterGenerationRead() {
    }

    default void afterHeadersCapture() {
    }

    default void beforeDispatch() {
    }
}
