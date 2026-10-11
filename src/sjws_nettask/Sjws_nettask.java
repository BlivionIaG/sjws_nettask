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
import java.net.ServerSocket;
import java.util.Map;

/**
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public class Sjws_nettask {

    public Sjws_nettask(int port, String htmlPath) {
        try (var socket = new ServerSocket(port)) {
            System.out.println("Server started on port : " + port + " (serving " + htmlPath + ")");

            var listener = new Thread(new ClientManager(socket, htmlPath), "accept-loop");
            listener.start();
            listener.join();
        } catch (IOException e) {
            System.err.println("Failed to open the server on port " + port + " ! " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            System.out.println("Server Terminated properly !");
        }
    }

    public static boolean checkValidPort(String port) {
        if (port == null || !port.matches("\\d{1,5}")) {
            return false;
        }
        var value = Integer.parseInt(port);
        return value > 0 && value < 65536;
    }

    /**
     * Usage: [port] [html folder path]. The WEB_SERVER_PORT and WEB_SERVER_DIR
     * environment variables override the arguments.
     *
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        Map<String, String> env = System.getenv();
        var port = CONSTANTS.DEFAULT_PORT;
        var contentPath = CONSTANTS.DEFAULT_HTML_PATH;

        if (args.length > 0) {
            if (checkValidPort(args[0])) {
                port = Integer.parseInt(args[0]);
            } else {
                System.err.println("Invalid port '" + args[0] + "', using " + port);
            }
        }
        if (args.length > 1) {
            contentPath = args[1];
        }

        if (checkValidPort(env.get("WEB_SERVER_PORT"))) {
            port = Integer.parseInt(env.get("WEB_SERVER_PORT"));
        }
        if (env.containsKey("WEB_SERVER_DIR")) {
            contentPath = env.get("WEB_SERVER_DIR");
        }

        new Sjws_nettask(port, contentPath);
    }
}
