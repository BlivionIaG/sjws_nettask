/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package sjws_nettask;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.IOException;

/**
 *
 * @author BlivionIaG
 */
public class Slave implements Runnable {

    private ServerSocket server;
    private Socket client;
    private PrintWriter output;
    private BufferedReader input;
    private String id, html_path, received_message;

    public Slave(ServerSocket server, Socket client, String path) {
        System.out.println("Un client s'est connecté");
        this.server = server;
        this.client = client;
        this.received_message = null;
        this.html_path = path;
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
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void send(String msg) {
        try {
            output = new PrintWriter(client.getOutputStream());
            output.println(msg);
            output.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean interpreter(String message) throws IOException {
        System.out.println(message);
        String[] parsed_message = message.split("§");
        switch (parsed_message.length) {
            case 1:
                String[] not_nts = message.split(" ");
                if (not_nts[0].equals("GET") && not_nts[2].split("/")[0].equals("HTTP")) {
                    new HTML(html_path).send(this.client, not_nts[1]);
                    return false;
                } else {
                    System.out.println(this.id + " : " + message);
                }
                break;
            default:
                System.out.println("This message is not interpretable.");
                break;
        }
        return true;
    }
}
