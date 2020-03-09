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
import java.util.HashMap;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public class Client implements Runnable {

    protected Socket client;
    protected String id;
    protected final HashMap<Thread, Client> clients;
    protected PrintWriter output;
    protected BufferedReader input;

    protected volatile boolean running = true;
    protected volatile boolean paused = false;
    protected final Object pauseLock = new Object();

    public Client(Client _copy) {
        client = _copy.getClient();
        id = _copy.getClientId();
        clients = _copy.getClients();
        output = _copy.getOutput();
        input = _copy.getInput();
    }

    public Client(HashMap<Thread, Client> clients, Socket _client) {
        this.clients = clients;
        this.id = UUID.randomUUID().toString().replaceAll("-", "");
        setClient(_client);
    }

    public void setClient(Socket _client) {
        this.client = _client;
        try {
            output = new PrintWriter(client.getOutputStream());
            input = new BufferedReader(new InputStreamReader(client.getInputStream()));
        } catch (IOException ex) {
            Logger.getLogger(Client.class.getName()).log(Level.SEVERE, null, ex);
        }

        System.out.println("A Client is connected ! (" + this.client + ")");

    }

    @Override
    public void run() {
        while (running) {
            synchronized (pauseLock) {
                if (!running) { // may have changed while waiting to
                    // synchronize on pauseLock
                    break;
                }
                if (paused) {
                    try {
                        synchronized (pauseLock) {
                            pauseLock.wait(); // will cause this Thread to block until 
                            // another thread calls pauseLock.notifyAll()
                            // Note that calling wait() will 
                            // relinquish the synchronized lock that this 
                            // thread holds on pauseLock so another thread
                            // can acquire the lock to call notifyAll()
                            // (link with explanation below this code)
                        }
                    } catch (InterruptedException ex) {
                        break;
                    }
                    if (!running) { // running might have changed since we paused
                        break;
                    }
                }
            }
            loop();
        }
        this.close();
    }
    
    public void loop(){
        
    }

    public String receive() {
        String receivedMessage = "";
        try {
            do {
                if ((receivedMessage = input.readLine()) == null) {
                    // "null" when client not connected to the server
                    //close();
                    pause();
                    return "";
                }

                try {
                    Thread.sleep(100); // Decrease CPU load by sleeping every 100ms (very effective)
                } catch (InterruptedException e) {
                    System.err.println(e);
                }
            } while (receivedMessage.equals("")); // Trying to get a message (can be improved ?)
            System.out.println("The server has received : " + receivedMessage);
        } catch (IOException e) {
            System.err.println(e);
        }

        return receivedMessage;
    }

    public void send(String message) {
        output.println(message);

        if (output.checkError()) {
            System.err.println("The server has failed to send the client : " + message);
        } else {
            System.out.println("The server has sent to the client : " + message);
        }
    }

    public Socket getClient() {
        return this.client;
    }

    public void setClientId(String _id) {
        id = _id;
    }

    public String getClientId() {
        return this.id;
    }

    public HashMap<Thread, Client> getClients() {
        return this.clients;
    }

    public PrintWriter getOutput() {
        return this.output;
    }

    public BufferedReader getInput() {
        return this.input;
    }

    public int find(String id) {
        synchronized (this) { // Synchronised access
            for (var i = 0; i < this.clients.size(); i++) {
                if (this.clients.get(i) != null && this.clients.get(i).getClientId().equals(id)) { // ID matching
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
                input.close();
                output.close();
            }
        } catch (IOException e) {
            System.err.println("Error while trying to client socket ! \n " + e);
        } finally {
            System.out.println("Client socket closed ! (" + this.client + ")");
        }

        synchronized (this) {                           //Synchronised access
            for (var i = 0; i < this.clients.size(); i++) {
                if (this.clients.get(i) == this) {           // Client found
                    this.clients.remove(Thread.currentThread());
                }
            }
        }

        Thread.currentThread().interrupt(); // Thread Interruption
    }

    public static boolean isNumeric(String strNum) {
        if (strNum == null) {
            return false;
        }
        try {
            double d = Double.parseDouble(strNum);
        } catch (NumberFormatException nfe) {
            return false;
        }
        return true;
    }

    public void stop() {
        running = false;
        // you might also want to interrupt() the Thread that is 
        // running this Runnable, too, or perhaps call:
        resume();
        // to unblock
    }

    public void pause() {
        // you may want to throw an IllegalStateException if !running
        paused = true;
    }

    public void resume() {
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll(); // Unblocks thread
        }
    }
}
