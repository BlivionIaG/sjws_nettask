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
package sjws_nettask.HTTP;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.nio.ByteBuffer;
import sjws_nettask.CONSTANTS;
import sjws_nettask.Tools;

/**
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public final class Page {
    private Header header;
    private String date;
    private final String path;

    public Page(String path) {
        this.path = path;
        this.date = Tools.formatedCurrentDate();
    }

    public void send(Socket client) throws IOException {
        var outputStream = new BufferedOutputStream(client.getOutputStream());
        var buffer = ByteBuffer.allocateDirect(CONSTANTS.HTML_BUFFER_SIZE);

        try (var raFile = new RandomAccessFile(this.path, "r"); var inputChannel = raFile.getChannel()) {

            this.header = new Header(200, this.path, raFile.length());

            System.out.println("Sending " + this.path + "(" + raFile.length() + " bytes)");
            outputStream.write(this.header.toString().getBytes());
            while (inputChannel.read(buffer) > 0) {
                buffer.flip();
                for (var i = 0; i < buffer.limit(); i++) {
                    outputStream.write(buffer.get());
                }
                buffer.compact();
            }
            outputStream.flush();

        }
    }

    public void sendFolder(Socket client) throws IOException {
        var outputStream = new BufferedOutputStream(client.getOutputStream());
        var folder = new File(path);
        var list = folder.listFiles();

        String temp = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><title>"
                + this.path
                + "</title></head><body><h1>"
                + this.path
                + "</h1>";

        for (var list1 : list) {
            temp += "<a href=\"./" + list1.getName() + "\"> " + list1.getName() + " </a><br>";
        }
        temp += "</body></html>";

        this.header = new Header(200, this.path, "text/html", temp.length());

        String page = this.header.toString() + "\r\n\r\n" + temp;
        System.out.println("Sending " + this.path + " (" + page.length() + " bytes)");
        outputStream.write(page.getBytes());
        outputStream.flush();
    }
}
