package sjws_nettask;

import java.net.ServerSocket;
import java.io.IOException;

/**
 *
 * @author BlivionIaG
 */
public class Sjws_nettask {

    private ServerSocket server;

    public Sjws_nettask(int port, String path) {
        try {
            this.server = new ServerSocket(port);
            System.out.println("Server started at port : " + port);
            Thread scred = new Thread(new New_client(server, path));

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
     * @param args arg1 = Port Number arg2 = files path
     */
    public static void main(String[] args) {
        String path = Defines.HTML_PATH;
        int port = Defines.PORT;

        if (args.length > 1) {
            path = args[1];
        }
        if (args.length > 0 && is_numeric(args[0])
                && Integer.parseInt(args[0]) > 0
                && Integer.parseInt(args[0]) < 65536) {
            port = Integer.parseInt(args[0]);
        }
        new Sjws_nettask(port, path);
    }

}
