package sjws_nettask;

import java.net.ServerSocket;
import java.net.Socket;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 *
 * @author BlivionIaG
 */
public class New_client implements Runnable {

    private ServerSocket server;
    private Socket client;
    private PrintWriter output;
    private ArrayList<Thread> threads;

    public New_client(ServerSocket server) {
        this.server = server;
        threads = new ArrayList<>();
    }

    public void run() {
        try {
            while (true) {
                new Thread(new Slave(server, (client = server.accept()))).start();
            }
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

    public void close() {
        try {
            server.close();
            client.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
