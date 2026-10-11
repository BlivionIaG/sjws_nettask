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
package sjws_nettask;

import java.util.Map;

/**
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public class CONSTANTS {

    public final static int DEFAULT_PORT = 8080; // Default Port
    public final static String DEFAULT_HTML_PATH = "./HTML";
    public final static int MAX_LINE_LENGTH = 8192; // request line / header line limit
    public final static int MAX_HEADERS = 100;
    public final static long MAX_DRAINED_BODY = 64 * 1024; // larger bodies on GET/HEAD: answer then close

    /** @deprecated use {@link sjws_nettask.core.Mime} */
    @Deprecated
    public static final Map<String, String> MIME_TYPE = sjws_nettask.core.Mime.types();
}
