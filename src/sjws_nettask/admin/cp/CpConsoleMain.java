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
package sjws_nettask.admin.cp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;

/**
 * {@code java -cp "target/sjws_nettask-<v>.jar:target/lib/*" sjws_nettask.admin.cp.CpConsoleMain --demo
 * [--endpoints=cp-1:9443,cp-2:9443] [--no-color]}
 * <p>
 * Mockup: only the in-memory demo control plane exists until HA ships the operator API. This class
 * is never loaded by the server, so the console costs nothing when it is not used.
 */
public final class CpConsoleMain {

    private CpConsoleMain() {
    }

    public static void main(String[] args) throws IOException {
        int code = run(args, System.in, System.out);
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(String[] args, InputStream in, PrintStream out) throws IOException {
        boolean demo = false;
        boolean color = true;
        List<String> endpoints = List.of("cp-1:9443", "cp-2:9443", "cp-3:9443");
        for (var a : args) {
            if (a.equals("--demo")) {
                demo = true;
            } else if (a.equals("--no-color")) {
                color = false;
            } else if (a.startsWith("--endpoints=") && a.length() > "--endpoints=".length()) {
                endpoints = Arrays.asList(a.substring("--endpoints=".length()).split(","));
            } else {
                out.println("ERROR: unknown argument " + a);
                return 64;
            }
        }
        if (!demo) {
            out.println("Usage: CpConsoleMain --demo [--endpoints=a,b,c] [--no-color]");
            out.println("Only the in-memory demo control plane is available until the HA operator API exists.");
            return 64;
        }
        var fake = FakeCpClient.demo(Clock.systemUTC());
        var client = new FailoverCpClient(endpoints, fake::endpoint);
        new CpConsole(client, color).run(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)), out);
        return 0;
    }
}
