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

    public Sjws_nettask(int port) {
        try {
            var socket = new ServerSocket(port);
            System.out.println("Server started on port : " + port);

            var listener = new Thread(new ClientManager(socket));
            listener.start();

            while (listener.getState() != Thread.State.TERMINATED) {
                try {
                    Thread.sleep(500); // Wait 500ms, reducing CPU load
                } catch (InterruptedException e) {
                    System.err.println(e);
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to open the server on port " + port + " !");
        } finally {
            System.out.println("Server Terminated properly !");
        }
    }

    public static boolean checkValidPort(String port) {
        return Tools.isNumeric(port) && Integer.parseInt(port) > 0 && Integer.parseInt(port) < 65536;
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        Map<String, String> env = System.getenv();
        var port = CONSTANTS.DEFAULT_PORT; // Declared default port value
        var contentPath = CONSTANTS.DEFAULT_HTML_PATH;

        if (args.length > 0 && checkValidPort(args[0])) {
            port = Integer.parseInt(args[0]);
        }

        if (env.containsKey("WEB_SERVER_PORT") && checkValidPort(env.get("WEB_SERVER_PORT"))) {
            port = Integer.parseInt(env.get("WEB_SERVER_PORT"));
        }
        
        if(env.containsKey("WEB_SERVER_DIR")){
            contentPath = env.get("WEB_SERVER_DIR");
        }

        var sjws_nettask = new Sjws_nettask(port);
    }

}
