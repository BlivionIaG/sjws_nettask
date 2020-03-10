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

import com.google.gson.Gson;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;

/**
 *
 * @author bevin
 */
public class Seller extends Client {

    private final Stock stock;
    private final Gson gson;

    public Seller(Client _client) {
        super(_client);

        gson = new Gson();
        stock = new Stock();
    }

    public Seller(HashMap<Thread, Client> _clients, SellerExport _se) {
        super(_clients, new Socket());

        gson = new Gson();
        stock = _se.stock;
        id = _se.id;
    }

    @Override
    public void loop() {
        if (!interpreter(receive())) {
            this.close();
        }
    }

    private boolean interpreter(String message) {
        boolean result = true;
        if (!message.equals("")) {
            var request = gson.fromJson(message, JsonCMD.class);

            if (request.command.equals("article_add")) {
                addArticle(request);
            } else if (request.command.equals("article_update")) {
                updateArticle(request);
            } else if (request.command.equals("article_del")) {
                deleteArticle(request);
            } else if (request.command.equals("article_list")) {
                listArticle(request);
            } else if (request.command.toLowerCase().equals("quit")) {
                close();
            }
        }

        return result;
    }

    public void sendError(String cmd, String message) {
        sendCmdResult(cmd, "ERROR", message);
    }

    public void sendConfirm(String cmd, String message) {
        sendCmdResult(cmd, "SUCCESS", message);
    }

    public void sendCmdResult(String cmd, String resultType, String message) {
        var data = new HashMap<String, String>();
        data.put("title", cmd);
        data.put("message", message);

        var result = new JsonCMD(
                CONSTANTS.SERVER_ID,
                id,
                resultType,
                data
        );

        send(gson.toJson(result));
    }

    public void addArticle(JsonCMD request) {
        if (request.data.containsKey("name")) {
            String name = request.data.get("name");
            long quantity = 0;
            double price = 0;

            if (request.data.containsKey("quantity")) {
                String tmp = request.data.get("quantity");
                if (isNumeric(tmp)) {
                    quantity = Long.parseLong(tmp);
                }
            }

            if (request.data.containsKey("price")) {
                String tmp = request.data.get("price");
                if (isNumeric(tmp)) {
                    price = Double.parseDouble(tmp);
                }
            }

            if (stock.addArticle(name, price, quantity)) {
                sendConfirm(request.command, "Article successfully added !");
            } else {
                sendError(request.command, "Article already exists ...");
            }
        } else {
            sendError(request.command, "Wrong arguments ...");
        }
    }

    public void updateArticle(JsonCMD request) {
        if (request.data.containsKey("name")) {
            String name = request.data.get("name");
            long quantity = 0;
            double price = 0;

            if (request.data.containsKey("quantity")) {
                String tmp = request.data.get("quantity");
                if (isNumeric(tmp)) {
                    quantity = Long.parseLong(tmp);
                } else {
                    sendError(request.command, "Invalid quantity !");
                    return;
                }
            } else {
                sendError(request.command, "No quantity set ...");
                return;
            }

            if (request.data.containsKey("price")) {
                String tmp = request.data.get("price");
                if (isNumeric(tmp)) {
                    price = Double.parseDouble(tmp);
                } else {
                    sendError(request.command, "Invalid price !");
                    return;
                }
            } else {
                sendError(request.command, "No price given ...");
                return;
            }

            if (stock.updateArticle(name, price, quantity)) {
                sendConfirm(request.command, "Article successfully updated !");
            } else {
                sendError(request.command, "Article doesn't exists ...");
            }
        } else {
            sendError(request.command, "Wrong arguments ...");
        }
    }

    public void deleteArticle(JsonCMD request) {
        if (request.data.containsKey("name")) {
            String name = request.data.get("name");

            if (stock.deleteArticle(name)) {
                sendConfirm(request.command, "Article successfully deleted !");
            } else {
                sendError(request.command, "Article doesn't exists ...");
            }
        } else {
            sendError(request.command, "Wrong arguments ...");
        }
    }

    public void listArticle(JsonCMD request) {
        var jsonArticles = new HashMap<String, String>();
        stock.getArticles().forEach((key, value) -> {
            jsonArticles.put(key, gson.toJson(value));
        });

        var result = new JsonCMD(
                CONSTANTS.SERVER_ID,
                id,
                request.command,
                jsonArticles
        );

        send(gson.toJson(result));
    }

    public Stock getStock() {
        return stock;
    }
}
