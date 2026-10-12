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
package sjws_nettask.admin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import sjws_nettask.ServerConfig;

/**
 * {@code java -cp target/sjws_nettask-<v>.jar:target/lib/* sjws_nettask.admin.AdminMain [server args]
 * [--log-file=PATH] [--no-color] [--no-autostart]}
 * <p>
 * Server arguments are the ones of the main entry point; they are re-parsed on reload. The server
 * still writes its own warnings to stderr: redirect it ({@code 2>server.log --log-file=server.log})
 * to keep the screen clean and see them in the logs view.
 */
public final class AdminMain {

    private AdminMain() {
    }

    public static void main(String[] args) throws IOException {
        int code = run(args, System::getenv, System.in, System.out, EmbeddedServerControl::new);
        if (code != 0) {
            System.exit(code);
        }
    }

    /** Options of the console itself, separated from the server's. */
    record Options(Path logFile, boolean color, boolean autostart, String[] serverArgs) {

        static Options parse(String[] args) {
            Path logFile = null;
            boolean color = true;
            boolean autostart = true;
            var rest = new ArrayList<String>();
            for (var a : args) {
                if (a.startsWith("--log-file=")) {
                    logFile = Path.of(a.substring("--log-file=".length()));
                } else if (a.equals("--no-color")) {
                    color = false;
                } else if (a.equals("--no-autostart")) {
                    autostart = false;
                } else {
                    rest.add(a);
                }
            }
            return new Options(logFile, color, autostart, rest.toArray(String[]::new));
        }
    }

    /** Re-parses the server arguments; throws with the parse errors when they are invalid. */
    static Supplier<ServerConfig> configSource(String[] serverArgs, Supplier<Map<String, String>> env) {
        return () -> {
            var parsed = ServerConfig.parse(serverArgs, env.get());
            if (!parsed.ok()) {
                throw new IllegalArgumentException(String.join("; ", parsed.errors()));
            }
            return parsed.config();
        };
    }

    /** @return process exit code (64 on bad arguments) */
    static int run(String[] args, Supplier<Map<String, String>> env, InputStream in, PrintStream out,
            BiFunction<Supplier<ServerConfig>, LogBuffer, ServerControl> controlFactory) throws IOException {
        var options = Options.parse(args);
        var parsed = ServerConfig.parse(options.serverArgs(), env.get());
        if (parsed.helpRequested()) {
            out.print(ServerConfig.usage());
            out.println("Admin console options: --log-file=PATH  --no-color  --no-autostart");
            return 0;
        }
        if (!parsed.ok()) {
            parsed.errors().forEach(e -> out.println("ERROR: " + e));
            return 64;
        }
        var log = new LogBuffer(500);
        try (var control = controlFactory.apply(configSource(options.serverArgs(), env), log)) {
            parsed.warnings().forEach(w -> log.add("WARN: " + w));
            if (options.autostart()) {
                try {
                    control.start();
                } catch (ServerControl.ControlException e) {
                    // shown as FAILED in the status view
                }
            }
            new AdminTui(control, log, options.logFile(), options.color())
                    .run(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)), out);
        }
        return 0;
    }
}
