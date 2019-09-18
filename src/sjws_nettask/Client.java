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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;

import sjws_nettask.HTTP.HTML;

/**
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public class Client extends Thread {

    private final Socket client;
    private String id, received_message;
    private final ArrayList<Client> threads;

    public Client(ArrayList<Client> threads, Socket client) {
        this.threads = threads;
        this.client = client;

        this.received_message = "";

        System.out.println("A Client is connected ! (" + this.client + ")");
    }

    @Override
    public void run() {
        try {
            var input = new BufferedReader(new InputStreamReader(this.client.getInputStream()));
            
            do {
                this.received_message = "";
                do {
                    this.received_message = input.readLine();

                    if (this.received_message == null) { // "null" when client not connected to the server
                        this.close(); // closing the thread
                        return;
                    }

                    try {
                        Thread.sleep(100); // Decrease CPU load by sleeping every 100ms (very effective)
                    } catch (InterruptedException e) {
                        System.err.println(e);
                    }
                } while (this.received_message.equals("")); // Trying to get a message (can be improved ?)
            } while (this.interpreter(this.received_message));
            
            input.close();
        } catch (IOException e) {
            System.err.println(e);
        }

        this.close();
    }

    private boolean interpreter(String message) throws IOException {
        System.out.println(message);

        var parsed_message = message.split("§");

        switch (parsed_message.length) {
            case 1:
                if (parsed_message[0].toUpperCase().equals("STOP") || parsed_message[0].toUpperCase().equals("EXIT")) {
                    return false;
                } else {
                    var spacedMessage = message.split(" ");
                    if (spacedMessage[0].equals("GET") && spacedMessage[2].split("/")[0].equals("HTTP")) {
                        new HTML().send(this.client, spacedMessage[1]);
                    }
                }
                break;
            default:
                System.out.println("This message is not interpretable.");
                break;
        }

        return true;
    }

    public void send(String message) {
        try {
            var output = new PrintWriter(client.getOutputStream());

            output.println(message);
            if (!output.checkError()) {
                output.close();
            }

        } catch (IOException e) {
            System.err.println("An error has occured while sending ! \n" + e);
            this.close();
        } finally {
            System.out.println("The server has sent to the client : " + message);
        }
    }

    public String getClientId() {
        return this.id;
    }

    public int find(String id) {
        synchronized (this) { // Synchronised access
            for (var i = 0; i < this.threads.size(); i++) {
                if (this.threads.get(i) != null && this.threads.get(i).getClientId().equals(id)) { // ID matching
                    return i;
                }
            }
        }

        return -1; // Nothing found
    }

    public void close() {
        System.out.println("Closing client : " + this);

        try {
            if (this.client != null) {
                this.client.close();
            }
        } catch (IOException e) {
            System.err.println("Error while trying to client socket ! \n " + e);
        } finally {
            System.out.println("Client socket closed ! (" + this.client + ")");
        }

        synchronized (this) {                           //Synchronised access
            for (var i = 0; i < this.threads.size(); i++) {
                if (this.threads.get(i) == this) {           // Client found
                    this.threads.set(i, null);               // Set to "null"
                }
            }
        }

        this.interrupt(); // Thread Interruption
    }
}
