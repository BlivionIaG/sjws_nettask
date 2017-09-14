package sjws_nettask;

import java.net.ServerSocket;
import java.io.IOException;

/**
 *
 * @author BlivionIaG
 */
public class Sjws_nettask {

    private ServerSocket server;

    public Sjws_nettask(int port) {
        try {
            this.server = new ServerSocket(port);
            System.out.println("Server started at port : " + port);
            Thread scred = new Thread(new New_client(server));

            scred.start();
            while (scred.getState() != Thread.State.TERMINATED);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static boolean is_numeric(String value) {
        return value != null && value.matches("[-+]?\\d*\\.?\\d+");
    }

    /**
     * @param args arg1 = Port Number
     */
    public static void main(String[] args) {
        if (args.length > 2 && is_numeric(args[1])
                && Integer.parseInt(args[1]) > 0
                && Integer.parseInt(args[1]) < 65536) {
            new Sjws_nettask(Integer.parseInt(args[1]));
        } else {
            new Sjws_nettask(Defines.PORT);
        }
    }

}
