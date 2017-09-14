package sjws_nettask;

import java.io.IOException;
import java.io.BufferedOutputStream;
import java.net.Socket;
import java.util.Calendar;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.io.File;

/**
 *
 * @author BlivionIaG
 */

public class HTML {

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

    public HTML() {
    }

    public void send(Socket client, String path) throws IOException {

        String final_path = path.equals("/") ? "index.html" : path;
        BufferedOutputStream output_stream = new BufferedOutputStream(client.getOutputStream());

        if (!new File(Defines.HTML_PATH + final_path).isDirectory()) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(Defines.HTML_BUFFER_SIZE);
            RandomAccessFile ra_file = new RandomAccessFile(Defines.HTML_PATH + final_path, "r");
            FileChannel input_channel = ra_file.getChannel();

            System.out.println("Sending " + final_path + "(" + ra_file.length() + " bytes)");
            output_stream.write((initHtmlHeader(final_path) + ra_file.length() + "\r\n\r\n").getBytes());
            while (input_channel.read(buffer) > 0) {
                buffer.flip();
                for (int i = 0; i < buffer.limit(); i++) {
                    output_stream.write(buffer.get());
                }
                buffer.compact();
            }
            output_stream.flush();

            input_channel.close();
            ra_file.close();
        } else {
            File folder = new File(Defines.HTML_PATH + final_path);
            File[] list = folder.listFiles();
            String page = initHtmlHeader("index.html"),
                    temp = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><title>"
                    + final_path
                    + "</title></head><body><h1>"
                    + final_path
                    + "</h1>";

            for (int i = 0; i < list.length; i++) {
                temp += "<a href=\"./"
                        + list[i].getName()
                        + "\"> "
                        + list[i].getName()
                        + " </a><br>";
            }
            temp += "</body></html>";

            page += (page.length() + 4 + temp.length()) + "\r\n\r\n" + temp;
            System.out.println("Sending " + final_path + " (" + page.length() + " bytes)");
            output_stream.write(page.getBytes());
            output_stream.flush();
        }
    }

    public static String getHtmlDate() {
        Calendar now = Calendar.getInstance();
        String out = "Date: "
                + DAYS[now.get(Calendar.DAY_OF_WEEK) - 1]
                + ", " + now.get(Calendar.DAY_OF_MONTH)
                + " " + MONTHS[now.get(Calendar.MONTH)]
                + " " + now.get(Calendar.YEAR);
        if (now.get(Calendar.AM_PM) != 0) {
            out += now.get(Calendar.HOUR) + 12;
        } else {
            out += now.get(Calendar.HOUR);
        }
        out += ":"
                + now.get(Calendar.MINUTE)
                + ":"
                + now.get(Calendar.SECOND)
                + " GMT";

        return out;
    }

    public static String getMime(String path) {
        int i = path.length() - 1;
        while (path.charAt(i) != '.' && i >= 0) {
            i--;
        }

        String type = path.substring(i + 1);
        String out;

        if (type.equals("html")) {
            out = "text/html";
        } else if (type.equals("css")) {
            out = "text/css";
        } else if (type.equals("js")) {
            out = "application/javascript";
        } else if (type.equals("txt")) {
            out = "text/plain";
        } else if (type.equals("zip")) {
            out = "appliction/zip";
        } else if (type.equals("png")) {
            out = "image/png";
        } else if (type.equals("jpeg") || type.equals("jpg")) {
            out = "image/jpeg";
        } else if (type.equals("gif")) {
            out = "image/gif";
        } else if (type.equals("mp3")) {
            out = "audio/mpeg";
        } else if (type.equals("ogg")) {
            out = "application/ogg";
        } else if (type.equals("mp4")) {
            out = "video/mp4";
        } else if (type.equals("webm")) {
            out = "video/webm";
        } else {
            out = "text/html";
        }

        return out;
    }

    public static String initHtmlHeader(String path) {
        String header = "HTTP/1.1 200 OK\n";
        header += "Server: IaGy_Veb_Server/0.1\n";
        header += getHtmlDate() + "\n";
        header += "Last-Modified: Wed, 13 Sep 2017 16:32:54 GMT\n";
        header += "Accept-Ranges: bytes\n";
        header += "Content-Type: " + getMime(path) + "\n";
        header += "Connection: keep-alive\n";
        header += "Content-Length: ";

        return header;
    }

    public static String[] splitStringEvery(String s, int interval) {
        int arrayLength = (int) Math.ceil(((s.length() / (double) interval)));
        String[] result = new String[arrayLength];

        int j = 0;
        int lastIndex = result.length - 1;
        for (int i = 0; i < lastIndex; i++) {
            result[i] = s.substring(j, j + interval);
            j += interval;
        }
        result[lastIndex] = s.substring(j);

        return result;
    }
}
