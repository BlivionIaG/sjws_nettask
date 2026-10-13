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
package sjws_nettask.cp;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** Subprocess for {@link ClassLoadTest}: starts a witness CONTROL host and prints a marker. */
final class CpWitnessLoadMain {

    private CpWitnessLoadMain() {
    }

    public static void main(String[] args) throws Exception {
        var dir = Files.createTempDirectory("cp-witness");
        var peers = List.<Object>of(Map.of("id", "cp-1", "address", "127.0.0.1:21201"),
                Map.of("id", "cp-2", "address", "127.0.0.1:21202"),
                Map.of("id", "cp-3", "address", "127.0.0.1:21203", "witness", true));
        var yaml = Map.<String, Object>of("nodeId", "cp-3", "stateDir", dir.resolve("s").toString(), "keyDir",
                dir.resolve("k").toString(), "rootPub", dir.resolve("root.pub").toString(), "watch",
                Map.of("listen", "127.0.0.1:0"), "admin", Map.of("listen", "127.0.0.1:0"), "peers", peers);
        try (var host = CpLaunch.start(CpConfig.parse(k -> null, Map.of(), yaml, null), new CpTestDeps(), System.err)) {
            Thread.sleep(300);
            System.out.println("witness up");
            System.out.flush();
        }
    }
}
