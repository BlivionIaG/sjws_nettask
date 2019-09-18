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
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.nio.ByteBuffer;
import sjws_nettask.CONSTANTS;

/**
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public final class Page {

    public static final String PROTOCOL = "1.1";
    public static final String DEFAULT_SERVER_NAME = "IaGgy_Veb_Server";
    public static final String DEFAULT_SERVER_VERSION = "0.1";

    public static final String[] DAYS = {
        "Sun",
        "Mon",
        "Tue",
        "Wed",
        "Thu",
        "Fri",
        "Sat"
    };

    public static final String[] MONTHS = {
        "Jan",
        "Feb",
        "Mar",
        "Apr",
        "May",
        "Jun",
        "Jul",
        "Aug",
        "Sep",
        "Oct",
        "Nov",
        "Dec"
    };

    public static final Map<String, String> MIME_TYPE;

    static {
        Map<String, String> map = new HashMap<>();
        map.put("html", "text/html");
        map.put("css", "text/css");
        map.put("js", "application/javascript");
        map.put("txt", "text/plain");
        map.put("zip", "application/zip");
        map.put("png", "image/png");
        map.put("jpeg", "image/jpeg");
        map.put("jpg", "image/jpeg");
        map.put("gif", "image/gif");
        map.put("mp3", "audio/mpeg");
        map.put("ogg", "application/ogg");
        map.put("mp4", "video/mp4");
        map.put("webm", "video/webm");
        map.put("pdf", "application/pdf");
        map.put("", "text/html");

        MIME_TYPE = Collections.unmodifiableMap(map);
    }

    private String header;
    private String date;
    private final String path;

    public Page(String path) {
        System.out.println(path);
        this.path = path;

        this.buildDate();
    }

    public void buildHeader(long fileSize) {
        this.header = "HTTP/" + PROTOCOL + " 200 OK\n"
                + "Server: " + DEFAULT_SERVER_NAME + "/" + DEFAULT_SERVER_VERSION + "\n"
                + this.date + "\n"
                + "Last-Modified: Wed, 13 Sep 2017 16:32:54 GMT\n"
                + "Accept-Ranges: bytes\n"
                + "Content-Type: " + getMime(this.path) + "\n"
                + "Connection: keep-alive\n"
                + "Content-Length: " + fileSize + "\r\n\r\n";
    }
    
    public void buildHeader(String mime, long fileSize) {
        this.header = "HTTP/" + PROTOCOL + " 200 OK\n"
                + "Server: " + DEFAULT_SERVER_NAME + "/" + DEFAULT_SERVER_VERSION + "\n"
                + this.date + "\n"
                + "Last-Modified: Wed, 13 Sep 2017 16:32:54 GMT\n"
                + "Accept-Ranges: bytes\n"
                + "Content-Type: " + mime + "\n"
                + "Connection: keep-alive\n"
                + "Content-Length: " + fileSize + "\r\n\r\n";
    }

    public void buildDate() {
        Calendar now = Calendar.getInstance();
        date = "Date: "
                + DAYS[now.get(Calendar.DAY_OF_WEEK) - 1]
                + ", " + now.get(Calendar.DAY_OF_MONTH)
                + " " + MONTHS[now.get(Calendar.MONTH)]
                + " " + now.get(Calendar.YEAR);
        if (now.get(Calendar.AM_PM) != 0) {
            date += now.get(Calendar.HOUR) + 12;
        } else {
            date += now.get(Calendar.HOUR);
        }
        date += ":"
                + now.get(Calendar.MINUTE)
                + ":"
                + now.get(Calendar.SECOND)
                + " GMT";
    }

    public String getMime(String path) {
        int i;
        for (i = path.length() - 1; path.charAt(i) != '.' && i >= 0; i--);

        System.out.println(MIME_TYPE.getOrDefault(path.substring(i + 1), "application/octet-stream"));
        
        return MIME_TYPE.getOrDefault(path.substring(i + 1), "application/octet-stream");
    }

    public void send(Socket client) throws IOException {
        var outputStream = new BufferedOutputStream(client.getOutputStream());
        var buffer = ByteBuffer.allocateDirect(CONSTANTS.HTML_BUFFER_SIZE);

        try (var raFile = new RandomAccessFile(path, "r"); var inputChannel = raFile.getChannel()) {

            this.buildHeader(raFile.length());

            System.out.println("Sending " + path + "(" + raFile.length() + " bytes)");
            outputStream.write(this.header.getBytes());
            while (inputChannel.read(buffer) > 0) {
                buffer.flip();
                for (int i = 0; i < buffer.limit(); i++) {
                    outputStream.write(buffer.get());
                }
                buffer.compact();
            }
            outputStream.flush();

        }
    }

    public void sendFolder(Socket client) throws IOException {
        var outputStream = new BufferedOutputStream(client.getOutputStream());
        File folder = new File(path);
        File[] list = folder.listFiles();

        String temp = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><title>"
                + path
                + "</title></head><body><h1>"
                + path
                + "</h1>";

        for (File list1 : list) {
            temp += "<a href=\"./" + list1.getName() + "\"> " + list1.getName() + " </a><br>";
        }
        temp += "</body></html>";

        this.buildHeader("text/html", temp.length());

        String page = this.header + "\r\n\r\n" + temp;
        System.out.println("Sending " + path + " (" + page.length() + " bytes)");
        outputStream.write(page.getBytes());
        outputStream.flush();
    }
}
