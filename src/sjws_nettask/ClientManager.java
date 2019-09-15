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
import java.util.ArrayList;

/**
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public class ClientManager implements Runnable {

    private final ServerSocket server;
    private Socket client;
    private final ArrayList<Client> threads;

    public ClientManager(ServerSocket server) {
        this.server = server;

        this.threads = new ArrayList<>();
    }

    @Override
    public void run() {
        try {
            do {
                threads.add(new Client(threads, (client = server.accept()))); //Création et ajout d'un nouveau client quand il se connecte
                threads.get(threads.size() - 1).start(); //On démarre le thread du client
            } while (true);
        } catch (IOException e) { // Erreur de connexion 
            e.printStackTrace();
            close();
        }
    }

    public void close() {
        try {
            if (server != null) {
                server.close();
            }
            if (client != null) {
                client.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        Thread.currentThread().interrupt(); //Interruption du thread
    }
}
