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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * <pre>
 * java -cp "target/sjws_nettask-&lt;v&gt;.jar:target/lib/*" sjws_nettask.admin.cp.CpConsoleMain \
 *     --endpoints=cp-1:9443,cp-2:9443 --cert=operator.pem --key=operator-key.pem --trust=cp-ca.pem \
 *     [--key-password-file=FILE] [--no-color]
 * ... CpConsoleMain --demo [--endpoints=a,b] [--no-color]
 * </pre>
 * The live mode talks to the control plane's mTLS admin API through {@link FailoverCpClient}.
 * This class is never loaded by the server, so the console costs nothing when it is not used.
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

    static final String USAGE = """
            Usage: CpConsoleMain --endpoints=host:port[,host:port...] --cert=PEM --key=PEM --trust=PEM \
            [--key-password-file=FILE] [--no-color]
                   CpConsoleMain --demo [--endpoints=a,b,c] [--no-color]""";

    static int run(String[] args, InputStream in, PrintStream out) throws IOException {
        return run(args, in, out, null);
    }

    /** @param connector overrides the live endpoint connector (tests); null builds the mTLS client */
    static int run(String[] args, InputStream in, PrintStream out, Function<String, CpClient> connector)
            throws IOException {
        boolean demo = false;
        boolean color = true;
        List<String> endpoints = null;
        Path cert = null;
        Path key = null;
        Path trust = null;
        Path keyPassword = null;
        for (var a : args) {
            var value = a.indexOf('=') > 0 ? a.substring(a.indexOf('=') + 1) : "";
            if (a.equals("--demo")) {
                demo = true;
            } else if (a.equals("--no-color")) {
                color = false;
            } else if (a.startsWith("--endpoints=") && !value.isEmpty()) {
                endpoints = Arrays.asList(value.split(","));
            } else if (a.startsWith("--cert=") && !value.isEmpty()) {
                cert = Path.of(value);
            } else if (a.startsWith("--key=") && !value.isEmpty()) {
                key = Path.of(value);
            } else if (a.startsWith("--trust=") && !value.isEmpty()) {
                trust = Path.of(value);
            } else if (a.startsWith("--key-password-file=") && !value.isEmpty()) {
                keyPassword = Path.of(value);
            } else {
                out.println("ERROR: unknown argument " + a);
                out.println(USAGE);
                return 64;
            }
        }
        CpClient client;
        if (demo) {
            var fake = FakeCpClient.demo(Clock.systemUTC());
            client = new FailoverCpClient(endpoints == null ? List.of("cp-1:9443", "cp-2:9443", "cp-3:9443")
                    : endpoints, fake::endpoint);
        } else {
            if (endpoints == null || (connector == null && (cert == null || key == null || trust == null))) {
                out.println("ERROR: live mode needs --endpoints, --cert, --key and --trust (or --demo)");
                out.println(USAGE);
                return 64;
            }
            if (connector == null) {
                try {
                    var password = keyPassword == null ? null : Files.readString(keyPassword).strip();
                    var tls = HttpCpClient.tls(cert, key, password, trust);
                    connector = ep -> new HttpCpClient(ep, tls);
                } catch (IOException | GeneralSecurityException e) {
                    out.println("ERROR: cannot load the TLS material: " + e.getMessage());
                    return 64;
                }
            }
            client = new FailoverCpClient(endpoints, connector);
        }
        new CpConsole(client, color).run(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)), out);
        return 0;
    }
}
