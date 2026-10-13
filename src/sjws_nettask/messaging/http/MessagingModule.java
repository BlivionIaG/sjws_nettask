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
package sjws_nettask.messaging.http;

import java.nio.file.Path;

import sjws_nettask.OptionalModule;
import sjws_nettask.core.HandlerContributor;
import sjws_nettask.core.HandlerMounts;
import sjws_nettask.messaging.ledger.LedgerWriter;

/**
 * Mounts produce / fetch on plain log topics at {@code /messaging/v1} (docs/design/partition-log.md §2.2). Off by
 * default: loaded by name from {@code OptionalModules} only when {@code sjws.messaging.enabled} is true.
 */
public final class MessagingModule implements OptionalModule, HandlerContributor {

    static final String DIR_PROPERTY = "sjws.messaging.dir";
    static final String DIR_ENV = "SJWS_MESSAGING_DIR";

    private final LogTopics topics;

    public MessagingModule() {
        this(Path.of(setting(DIR_PROPERTY, DIR_ENV, "messaging-data")));
    }

    MessagingModule(Path dir) {
        this.topics = new LogTopics(dir, LedgerWriter.DEFAULT_DURABILITY);
    }

    static String setting(String property, String env, String dflt) {
        var v = System.getProperty(property);
        if (v == null || v.isBlank()) v = System.getenv(env);
        return v == null || v.isBlank() ? dflt : v.trim();
    }

    @Override
    public void contribute(HandlerMounts mounts) {
        mounts.mount("/messaging/v1", new MessagingHttp(this.topics));
    }

    @Override
    public void close() {
        this.topics.close();
    }
}
