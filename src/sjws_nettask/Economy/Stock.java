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
public class Stock {

    private final HashMap<String, Article> articles;

    public Stock() {
        articles = new HashMap<>();
    }

    public boolean addArticle(String name, double price, long quantity) {
        if (articles.containsKey(name)) { // Article already exists
            return false;
        }

        String tmp = new String(name);

        articles.put(tmp, new Article(tmp, price, quantity));

        return true;
    }

    public boolean updateArticle(String name, double price, long quantity) {
        if (!articles.containsKey(name)) { // Article doesn't exists
            return false;
        }

        var article = articles.get(name);
        article.setPrice(price);
        article.setQuantity(quantity);

        return true;
    }

    public boolean updateArticlePrice(String name, double price) {
        if (!articles.containsKey(name)) { // Article doesn't exists
            return false;
        }

        articles.get(name).setPrice(price);

        return true;
    }

    public boolean updateArticleQuantity(String name, long quantity) {
        if (!articles.containsKey(name)) { // Article doesn't exists
            return false;
        }

        articles.get(name).setQuantity(quantity);

        return true;
    }

    public boolean deleteArticle(String name) {
        if (!articles.containsKey(name)) {
            return false;
        }

        articles.remove(name);

        return true;
    }

    public HashMap<String, Article> getArticles() {
        return articles;
    }

    public ArrayList<Article> getArticlesList() {
        return new ArrayList<>(articles.values());
    }
}
