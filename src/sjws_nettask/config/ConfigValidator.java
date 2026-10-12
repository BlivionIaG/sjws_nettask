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
package sjws_nettask.config;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks a document without compiling a handler or swapping it in. An empty list means the
 * document can be applied. Messages name paths, not file bytes or passwords.
 */
public final class ConfigValidator {

    private ConfigValidator() {
    }

    public static List<String> validate(ConfigDocument document) {
        var errors = new ArrayList<String>();
        if (document.schemaVersion() != ConfigDocument.SCHEMA) {
            errors.add("schema: unsupported schema version " + document.schemaVersion());
        }
        var root = document.server().root();
        if (!Files.isDirectory(root)) {
            errors.add("Document root " + root.toAbsolutePath() + " does not exist or is not a directory");
        }
        var cert = document.server().tlsCert();
        var key = document.server().tlsKey();
        if ((cert == null) != (key == null)) {
            errors.add("tls.cert and tls.key must be given together");
        } else if (cert != null) {
            if (!Files.isRegularFile(cert)) {
                errors.add("tls.cert: not a file");
            }
            if (!Files.isRegularFile(key)) {
                errors.add("tls.key: not a file");
            }
        }
        if (cert != null && document.acme().enabled()) {
            errors.add("tls.cert and acme.enabled cannot both be set");
        }
        if (document.node().labels().size() > NodeSettings.MAX_LABELS) {
            errors.add("node.labels: at most " + NodeSettings.MAX_LABELS + " entries");
        }
        for (var entry : document.node().labels().entrySet()) {
            if (!NodeSettings.LABEL.matcher(entry.getKey()).matches()
                    || entry.getValue() == null
                    || !NodeSettings.LABEL.matcher(entry.getValue()).matches()) {
                errors.add("node.labels: invalid entry");
                break;
            }
        }
        return List.copyOf(errors);
    }
}
