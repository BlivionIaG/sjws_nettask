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

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import sjws_nettask.CONSTANTS;

/**
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public class HTML {

    private String folderPath;

    public HTML() {
        this.folderPath = CONSTANTS.DEFAULT_HTML_PATH;
    }

    public HTML(String htmlPath) {
        this.folderPath = htmlPath;
    }

    public String getFolderPath() {
        return this.folderPath;
    }

    public void setFolderPath(String path) {
        this.folderPath = path;
    }

    public void send(Socket client, String path) throws IOException {
        var file = new File(this.folderPath + path);

        if (file.isDirectory()) {
            if (path.equals("/") && new File(this.folderPath + "/index.html").exists()) {
                new Page(this.folderPath + "/index.html").send(client);
            } else {
                new Page(this.folderPath + path).sendFolder(client);
            }
        } else {
            if (!file.exists()) {

            } else {
                new Page(this.folderPath + path).send(client);
            }
        }
    }
}
