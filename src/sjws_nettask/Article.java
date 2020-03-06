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
package sjws_nettask;

/**
 *
 * @author bevin
 */
public class Article {
    private final String name;
    private double price;
    private long quantity;

    public Article(final String _name, double _price, long _quantity) {
        name = _name;
        price = _price;
        quantity = _quantity;
    }

    public String getName() {
        return name;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double _price) {
        price = _price;
    }
    
    public long getQuantity(){
        return quantity;
    }
    
    public void setQuantity(long _quantity){
        quantity = _quantity;
    }
    
    @Override
    public String toString(){
        return "["+name+"] \t\t"+ price +" \t\t("+quantity+")";
    }
}
