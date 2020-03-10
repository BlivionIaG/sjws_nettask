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

import sjws_nettask.Economy.Stock;

/**
 *
 * @author bevin
 */
public class SellerExport {
    public String id;
    public Stock stock;
    
    public SellerExport(Seller _seller){
        id = _seller.getClientId();
        stock = _seller.getStock();
    }
}
