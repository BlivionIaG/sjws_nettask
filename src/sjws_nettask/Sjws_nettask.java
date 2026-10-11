/*
 * Copyright (C) 2019 BlivionIaG <BlivionIaG at chenco.tk>
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
package sjws_nettask;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.nio.file.Files;

/**
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public class Sjws_nettask {

    private Sjws_nettask() {
    }

    /**
     * Runs the server until it is stopped.
     *
     * @return process exit code: 0 normal, 1 cannot listen, 2 bad document root
     */
    public static int serve(ServerConfig config) {
        var root = config.root();
        if (!Files.isDirectory(root)) {
            System.err.println("Document root " + root.toAbsolutePath() + " does not exist or is not a directory.");
            return 2;
        }
        int port = config.port();
        try (var socket = new ServerSocket(port)) {
            System.out.println("Server started on port : " + socket.getLocalPort() + " (serving " + root + ")");

            var listener = new Thread(new ClientManager(socket, config), "accept-loop");
            listener.start();
            listener.join();
            System.out.println("Server Terminated properly !");
            return 0;
        } catch (BindException e) {
            System.err.println("Cannot listen on port " + port + ": " + e.getMessage()
                    + " (already in use or not permitted).");
            return 1;
        } catch (IOException | UncheckedIOException e) {
            System.err.println("Failed to start the server on port " + port + ": " + e);
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    /** Kept for existing callers and tests. */
    public static boolean checkValidPort(String port) {
        if (port == null || !port.matches("\\d{1,5}")) {
            return false;
        }
        var value = Integer.parseInt(port);
        return value > 0 && value < 65536;
    }

    /**
     * Usage: [port] [html folder path] [--max-connections=N] [--idle-timeout=S] [--header-timeout=S].
     * WEB_SERVER_* environment variables override the arguments (see {@link ServerConfig}).
     *
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        var parsed = ServerConfig.parse(args, System.getenv());
        parsed.warnings().forEach(System.err::println);
        int code = serve(parsed.config());
        if (code != 0) {
            System.exit(code);
        }
    }
}
