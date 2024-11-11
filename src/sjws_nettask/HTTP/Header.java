/*
 * Copyright (C) 2024 BlivionIaG <kev29lt@gmail.com>
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

import sjws_nettask.CONSTANTS;
import sjws_nettask.Tools;

/**
 *
 * @author BlivionIaG <kev29lt@gmail.com>
 */
public class Header {

    public static final String DEFAULT_ACCEPT_RANGE = "bytes";
    public static final String DEFAULT_PROTOCOL = "1.1";
    public static final String DEFAULT_SERVER_NAME = "sjws_nettask web server";
    public static final String DEFAULT_SERVER_VERSION = "0.2";
    public static final String DEFAULT_CONNECTION = "keep-alive";

    private String acceptRanges;
    private String contentType;
    private long contentLength;
    private String server;
    private String serverVersion;
    private String connection;
    private String currentDate;
    private String lastModified;
    private String httpProtocol;
    private int httpCode;
    private String path;

    public Header(int httpCode, String path, long fileSize) {
        this.path = path;
        this.httpCode = httpCode;
        this.currentDate = Tools.formatedCurrentDate();
        this.lastModified = this.currentDate;
        this.contentType = getMime(path);
        this.contentLength = fileSize;
        this.server = DEFAULT_SERVER_NAME;
        this.serverVersion = DEFAULT_SERVER_VERSION;
        this.connection = DEFAULT_CONNECTION;
    }

    public Header(int httpCode, String path, String mime, long fileSize) {
        this.path = path;
        this.httpCode = httpCode;
        this.currentDate = Tools.formatedCurrentDate();
        this.lastModified = this.currentDate;
        this.contentType = mime;
        this.contentLength = fileSize;
        this.server = DEFAULT_SERVER_NAME;
        this.serverVersion = DEFAULT_SERVER_VERSION;
        this.connection = DEFAULT_CONNECTION;
    }

    public static String getMime(String path) {
        int extensionIndex = path.lastIndexOf('.');

        if (extensionIndex == -1 || extensionIndex == path.length() - 1) {
            return "application/octet-stream";
        }

        return CONSTANTS.MIME_TYPE.getOrDefault(path.substring(extensionIndex + 1), "application/octet-stream");
    }

    @Override
    public String toString() {
        return "HTTP/" + this.httpProtocol + " " + this.httpCode + " OK\n"
                + "Server: " + this.server + "/" + this.serverVersion + "\n"
                + this.currentDate + "\n"
                + "Last-Modified: " + this.lastModified + "\n"
                + "Accept-Ranges: " + this.acceptRanges + "\n"
                + "Content-Type: " + this.contentType + "\n"
                + "Connection: " + this.connection + "\n"
                + "Content-Length: " + this.contentLength + "\r\n\r\n";
    }
}
