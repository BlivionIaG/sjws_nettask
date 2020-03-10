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

import com.google.gson.Gson;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public class ClientManager implements Runnable {

    private final ServerSocket server;
    private Socket client;
    private final HashMap<Thread, Client> clients;

    private Gson gson = new Gson();

    public ClientManager(ServerSocket server) {
        this.server = server;

        this.clients = new HashMap<>();
        loadClients();
    }

    @Override
    public void run() {
        try {
            do {
                var tmpClient = new Client(clients, (client = server.accept())); //Création et ajout d'un nouveau client quand il se connecte

                /* ID Exchange */
                tmpClient.send(CONSTANTS.SERVER_ID);
                var clientID = tmpClient.receive();
                var recovClient = findClient(clientID);

                tmpClient.send(tmpClient.getClientId()); // Generated ID
                if (clientID.equals("0") || recovClient == null) {
                    var type = tmpClient.receive();
                    if (type.toUpperCase().equals("SELLER")) {
                        tmpClient = new Seller(tmpClient);
                    }

                    var tmpThread = new Thread(tmpClient);
                    clients.put(tmpThread, tmpClient);
                    tmpThread.start(); //On démarre le thread du client
                } else {
                    System.out.println("Session recovery !");
                    recovClient.setClient(tmpClient.getClient());
                    recovClient.receive();
                    clients.forEach((k, v) -> {
                        if (v == recovClient) {
                            v.resume();
                        }
                    });
                }
            } while (true);
        } catch (IOException e) { // Erreur de connexion 
            e.printStackTrace();
            close();
        }
    }

    public Client findClient(String id) {
        synchronized (this) { // Synchronised access
            var tmp = new ArrayList<>(clients.values());
            for (var it : tmp) {
                if (it.getClientId().equals(id)) {
                    return it;
                }
            }
        }

        return null; // Nothing found
    }

    public void saveClients() {
        var clientsSave = new ClientSave();
        clientsSave.clients = (List<Client>) clients.values();
        try {
            PrintWriter pw = new PrintWriter(new FileWriter("clients.json"));
            pw.println(gson.toJson(clientsSave));
        } catch (IOException ex) {
            Logger.getLogger(ClientManager.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public void loadClients() {
        var savedClients = "";
        try (var stream = Files.lines(Paths.get("clients.json"))) {
            var lines = stream.collect(Collectors.toList());
            for (var line : lines) {
                savedClients += line + "\n";
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        var tmpClients = gson.fromJson(savedClients, ClientSave.class).clients;
        for(var client : tmpClients){
            var tmpThread = new Thread(client);
            clients.put(tmpThread, client);
            tmpThread.start();
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

        saveClients();
        
        Thread.currentThread().interrupt(); //Interruption du thread
    }
}
