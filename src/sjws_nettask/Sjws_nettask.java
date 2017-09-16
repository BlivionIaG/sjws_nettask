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
