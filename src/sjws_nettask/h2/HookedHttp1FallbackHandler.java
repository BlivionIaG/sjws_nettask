/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package sjws_nettask.h2;

import java.util.Objects;

/** Test-only HTTP/1 fallback that runs {@link DispatchHooks#beforeDispatch()} before the Task. */
final class HookedHttp1FallbackHandler extends Http1FallbackHandler {

    private final DispatchHooks hooks;

    HookedHttp1FallbackHandler(Http2Server.Shared shared, DispatchHooks hooks) {
        super(shared);
        this.hooks = Objects.requireNonNull(hooks, "hooks");
    }

    @Override
    void executeTask(Runnable task) {
        this.shared.executor().execute(() -> {
            this.hooks.beforeDispatch();
            task.run();
        });
    }
}
