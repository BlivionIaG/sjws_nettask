/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
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
 * Where {@link SjwsServer} gets its HTTP/3 transport from. The h3 package (and Netty) is only
 * loaded when the factory is invoked, i.e. when {@code --h3} is set; a missing native library or
 * missing Netty classes make {@code SjwsServer} log a warning and serve HTTP/1.1 only, without Alt-Svc.
 */
public final class H3Transports {

    private H3Transports() {
    }

    public static Transport.Factory defaultFactory() {
        return new Transport.Factory() {
            @Override
            public Transport create(ServerConfig config, sjws_nettask.core.StaticFileHandler handler) {
                return new sjws_nettask.h3.Http3Server(config, handler);
            }

            @Override
            public Transport create(ServerConfig config, sjws_nettask.core.StaticFileHandler handler,
                    sjws_nettask.observe.RequestObserver observer) {
                return new sjws_nettask.h3.Http3Server(config, handler).withObserver(observer);
            }

            @Override
            public Transport create(ServerConfig config, sjws_nettask.core.StaticFileHandler handler,
                    sjws_nettask.observe.ObserverSlot slot) {
                return new sjws_nettask.h3.Http3Server(config, handler).withSlot(slot);
            }
        };
    }
}
