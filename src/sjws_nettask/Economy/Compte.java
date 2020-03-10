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

/**
 *
 * @author bevin
 */
public class Compte {
    private String name;
    private double solde;
    
    public Compte(String _name, double _solde){
        name = _name;
        solde = _solde;
    }
    
    public String getName(){
        return name;
    }
    
    public void setName(String _name){
        name = _name;
    }
    
    public double getSolde(){
        return solde;
    }
    
    public void setSolde(double _solde){
        solde = _solde;
    }
}
