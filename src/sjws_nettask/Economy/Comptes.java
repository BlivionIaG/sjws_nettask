/*
 * Copyright (C) 2020 bevin
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
package sjws_nettask.Economy;

import java.util.ArrayList;
import java.util.HashMap;

/**
 *
 * @author bevin
 */
public class Comptes {

    private final HashMap<String, ArrayList<Compte>> comptes;

    public Comptes() {
        comptes = new HashMap<>();
    }

    public boolean addCompte(String id, String name, double value) {
        if (!comptes.containsKey(id)) {
            comptes.put(id, new ArrayList<>());
        }
        comptes.get(id).add(new Compte(name, value));

        return true;
    }

    public boolean updateCompte(String id, String name, double value) {
        if (!comptes.containsKey(name)) {
            return false;
        }

        for (var compte : comptes.get(id)) {
            if (compte.getName().equals(name)) {
                compte.setSolde(value);
                return true;
            }
        }

        return false;
    }
    
    public boolean deleteCompte(String id, String name) {
        if (!comptes.containsKey(name)) {
            return false;
        }

        var tmpComptes = comptes.get(id);
        for (var i = 0; i < tmpComptes.size(); ++i) {
            if (tmpComptes.get(i).equals(name)) {
                tmpComptes.remove(i);
                return true;
            }
        }

        return false;
    }

    public HashMap<String, ArrayList<Compte>> getComptes() {
        return comptes;
    }
}
