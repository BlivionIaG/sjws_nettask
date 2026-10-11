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
import java.net.Socket;
import java.util.concurrent.Executors;

import sjws_nettask.HTTP.HTML;

/**
 * Accept loop: hands every connection to a virtual thread.
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public class ClientManager implements Runnable {

    private final ServerSocket server;
    private final HTML html;

    public ClientManager(ServerSocket server) {
        this(server, CONSTANTS.DEFAULT_HTML_PATH);
    }

    public ClientManager(ServerSocket server, String htmlPath) {
        this.server = server;
        this.html = new HTML(htmlPath);
    }

    @Override
    public void run() {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            while (!this.server.isClosed()) {
                Socket socket;
                try {
                    socket = this.server.accept();
                } catch (IOException e) {
                    if (this.server.isClosed()) {
                        break; // normal shutdown
                    }
                    System.err.println("accept() failed, still listening: " + e);
                    continue;
                }
                try {
                    socket.setSoTimeout(CONSTANTS.SOCKET_TIMEOUT_MS);
                    executor.execute(new Client(socket, this.html));
                } catch (IOException | RuntimeException e) {
                    System.err.println("Could not start client: " + e);
                    closeQuietly(socket);
                }
            }
        } finally {
            close();
            executor.shutdownNow(); // don't wait for idle keep-alive connections
        }
    }

    public void close() {
        closeQuietly(this.server);
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception e) {
            System.err.println(e);
        }
    }
}
