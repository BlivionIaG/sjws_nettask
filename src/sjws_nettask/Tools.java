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

import java.security.SecureRandom;
import java.util.Random;

/**
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public class Tools {

    /**
     *
     * @param value String to check
     * @return result of the verification
     */
    public static boolean is_numeric(String value) {
        return value != null && value.matches("[-+]?\\d*\\.?\\d+");
    }

    /**
     * Generate a random alphanumeric string at a given size
     *
     * @param charset Alphanumeric charset
     * @param length Size
     * @return Random String
     */
    public static String random_alpha_numeric(char[] charset, int length) {
        var random = new SecureRandom(); // Random values generator
        String result = "";

        for(var i = 0; i < length; ++i){
            result += charset[random.nextInt(charset.length)];
        }

        return result;
    }
}
