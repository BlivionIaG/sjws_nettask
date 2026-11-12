/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package sjws_nettask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Missing-config recovery gets the bootstrap pin from the same resolution as a normal boot. */
class RecoveryPinTest {

    @TempDir
    Path dir;

    @Test
    void recoveryUsesTheResolvedBootstrapPin() throws Exception {
        var pin = dir.resolve("root.pin");
        Files.writeString(pin, "root\n");
        var boot = dir.resolve("bootstrap");
        Files.writeString(boot, "role=data\nnode-id=d-1\npin=" + pin.toAbsolutePath() + "\nstate-dir="
                + dir.resolve("state").toAbsolutePath() + "\n");
        Files.setPosixFilePermissions(boot, PosixFilePermissions.fromString("rw-------"));

        assertEquals(pin.toAbsolutePath(), Sjws_nettask.recoveryPin(boot));
        assertEquals(pin.toAbsolutePath(), sjws_nettask.service.Bootstrap.pinFile(boot));
        var fromFlag = Daemon.parse(new String[] {"--bootstrap=" + boot, "--config=x.yaml"}, Map.of());
        assertEquals(pin.toAbsolutePath(), Sjws_nettask.recoveryPin(fromFlag.bootstrap()));
        var fromEnv = Daemon.parse(new String[] {"--config=x.yaml"}, Map.of("WEB_SERVER_BOOTSTRAP", boot.toString()));
        assertEquals(pin.toAbsolutePath(), Sjws_nettask.recoveryPin(fromEnv.bootstrap()));

        assertNull(Sjws_nettask.recoveryPin(null));
        assertNull(Sjws_nettask.recoveryPin(dir.resolve("missing-bootstrap")));
        Files.writeString(boot, "role=data\nnode-id=d-1\npin=relative.pin\n");
        assertNull(Sjws_nettask.recoveryPin(boot), "a pin a normal boot would refuse is not used");
    }
}
