/*
 * The MIT License
 *
 * Copyright 2017 BlivionIaG.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package sjws_nettask;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;
import java.security.SecureRandom;

/**
 *
 * @author BlivionIaG
 */
public class Slave extends Thread {

    private ServerSocket server;
    private Socket client;
    private PrintWriter output;
    private BufferedReader input;
    private String id, key, html_path, received_message;
    private ArrayList<Slave> threads;

    public Slave(ArrayList<Slave> threads, ServerSocket server, Socket client, String path) {
        System.out.println("Un client s'est connecté");
        this.server = server;
        this.client = client;
        this.received_message = null;
        this.html_path = path;
        this.threads = threads;
    }

    public void run() {
        try {
            do {
                received_message = null;
                do {
                    input = new BufferedReader(new InputStreamReader(client.getInputStream()));
                    received_message = input.readLine();
                } while (received_message == null);
            } while (interpreter(received_message));
        } catch (IOException e) {
            e.printStackTrace();
        }
        close();
    }

    public void send(String msg) {
        try {
            output = new PrintWriter(client.getOutputStream());
            output.println(msg);
            output.flush();
            System.out.println("SERVER: " + msg);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean interpreter(String message) throws IOException {
        if (Defines.DEBUG) {
            System.out.println(message);
        }
        String[] parsed_message = message.split("§");

        switch (parsed_message.length) {
            case 1:
                if (parsed_message[0].toUpperCase().equals("LIST")) {
                    String list = "";
                    synchronized (this) {
                        for (int i = 0; i < threads.size(); i++) {
                            if (threads.get(i) != null && threads.get(i).getSlaveId() != null) {
                                list += "§" + threads.get(i).getSlaveId();
                            }
                        }
                    }
                    if (list.length() > 0) {
                        send("SUCCESS§LIST" + list);
                    } else {
                        send("ERROR§LIST§EMPTY");
                    }
                } else if (parsed_message[0].toUpperCase().equals("EXIT")) {
                    send("MESSAGE§BYE !");
                    close();
                    return false;
                } else {
                    String[] not_nts = message.split(" ");
                    if (not_nts[0].equals("GET") && not_nts[2].split("/")[0].equals("HTTP")) {
                        new HTML(html_path).send(this.client, not_nts[1]);
                        return false;
                    }
                }
                break;
            case 2:
                if (parsed_message[0].toUpperCase().equals("GLOBAL")) {
                    synchronized (this) {
                        for (int i = 0; i < threads.size(); i++) {
                            if (threads.get(i) != null && threads.get(i) != this) {
                                threads.get(i).send("MESSAGE§" + this.id + "§" + parsed_message[1]);
                            }
                        }
                    }
                }
                break;
            case 3:
                if (parsed_message[0].toUpperCase().equals("REGISTER")) {
                    if (this.id != null) {
                        send("ERROR§REGISTER§ALREADY");
                        return true;
                    }
                    synchronized (this) {
                        for (int i = 0; i < threads.size(); i++) {
                            if (threads.get(i) != null && parsed_message[1].equals(threads.get(i).getSlaveId())) {
                                send("ERROR§REGISTER§EXIST");
                                return true;
                            }
                        }
                    }
                    this.id = parsed_message[1];
                    this.key = parsed_message[2];
                    send("SUCCESS§REGISTER");
                } else if (parsed_message[0].toUpperCase().equals("MESSAGE")) {
                    int index = find(parsed_message[1]);
                    if (index == -1) {
                        send("ERROR§MESSAGE§UNKNOWN");
                    } else {
                        synchronized (this) {
                            threads.get(index).send("MESSAGE§" + this.id + "§" + parsed_message[2]);
                        }
                    }
                }
                break;
            default:
                System.out.println("This message is not interpretable.");
                break;
        }
        System.out.println(this.id + " : " + message);

        return true;
    }

    public static String random_alpha_numeric(char[] charset, int length) {
        Random random = new SecureRandom();
        char[] result = new char[length];
        for (int i = 0; i < result.length; i++) {
            int randomCharIndex = random.nextInt(charset.length);
            result[i] = charset[randomCharIndex];
        }
        return new String(result);
    }

    public String getSlaveId() {
        return id;
    }

    public int find(String id) {
        synchronized (this) {
            for (int i = 0; i < threads.size(); i++) {
                if (threads.get(i) != null && threads.get(i).getSlaveId().equals(id)) {
                    return i;
                }
            }
        }
        return -1;
    }

    public void close() {
        try {
            input.close();
            client.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        synchronized (this) {
            for (int i = 0; i < threads.size(); i++) {
                if (threads.get(i) == this) {
                    threads.set(i, null);
                }
            }
        }
        this.interrupt();
    }
}
