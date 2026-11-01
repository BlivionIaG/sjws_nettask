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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Regression for #359: the config package JaCoCo branch floor must stay at least 0.85.
 * Fails on master before the gate bump.
 */
class ConfigJacocoGateTest {

    private static final Pattern CONFIG_RULE = Pattern.compile(
            "sjws_nettask\\.config</include></includes>\\s*<limits>(.*?)</limits>",
            Pattern.DOTALL);
    private static final Pattern BRANCH = Pattern.compile(
            "<counter>BRANCH</counter><value>COVEREDRATIO</value><minimum>([0-9.]+)</minimum>");
    private static final Pattern LINE = Pattern.compile(
            "<counter>LINE</counter><value>COVEREDRATIO</value><minimum>([0-9.]+)</minimum>");

    @Test
    void configPackageBranchGateIsAtLeastPointEightyFive() throws Exception {
        var pom = Path.of("pom.xml");
        assertTrue(Files.isRegularFile(pom), "pom.xml next to the module root");
        var text = Files.readString(pom);
        var rule = CONFIG_RULE.matcher(text);
        assertTrue(rule.find(), "sjws_nettask.config JaCoCo rule");
        var block = rule.group(1);
        var branch = BRANCH.matcher(block);
        assertTrue(branch.find(), "BRANCH minimum in config rule");
        assertTrue(Double.parseDouble(branch.group(1)) + 1e-9 >= 0.85,
                "config BRANCH minimum was " + branch.group(1));
        var line = LINE.matcher(block);
        assertTrue(line.find(), "LINE minimum in config rule");
        assertTrue(Double.parseDouble(line.group(1)) + 1e-9 >= 0.90,
                "config LINE minimum was " + line.group(1));
    }
}
