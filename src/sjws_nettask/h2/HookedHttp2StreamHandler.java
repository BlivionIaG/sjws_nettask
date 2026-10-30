/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package sjws_nettask.h2;

import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2StreamChannel;
import java.util.Objects;
import sjws_nettask.core.Request;

/**
 * Test-only stream handler that runs {@link DispatchHooks} around pin/dispatch. Production
 * pipelines use {@link Http2StreamHandler} so the hot path has no hook calls.
 */
final class HookedHttp2StreamHandler extends Http2StreamHandler {

    private final DispatchHooks hooks;

    HookedHttp2StreamHandler(Http2Server.Shared shared, DispatchHooks hooks) {
        super(shared);
        this.hooks = Objects.requireNonNull(hooks, "hooks");
    }

    @Override
    void captureAndStart(Http2StreamChannel stream, Http2Headers headers, boolean endStream) {
        Http2Server.Generation generation;
        Request request;
        for (;;) {
            generation = this.shared.generation();
            this.hooks.afterGenerationRead();
            if (!generation.tryEnter()) {
                continue;
            }
            request = toRequest(headers, Http2StreamHandler.peer(stream), Http2StreamHandler.secure(stream), generation.attachBodies());
            this.hooks.afterHeadersCapture();
            break;
        }
        start(stream, headers, request, endStream, generation);
    }

    @Override
    void executeTask(Runnable task) {
        this.shared.executor().execute(() -> {
            this.hooks.beforeDispatch();
            task.run();
        });
    }

}
