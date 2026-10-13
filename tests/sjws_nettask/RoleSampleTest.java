package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import sjws_nettask.config.ConfigDocument;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.config.ConfigValidator;
import sjws_nettask.config.NodeSettings;

/**
 * The packaged role samples must keep loading under schema 1. A renamed key or a rejected
 * {@code node.mode} fails this test in CI.
 */
@Timeout(30)
class RoleSampleTest {

    @Test
    void packagedSamplesMatchSchemaOne() {
        assertMode("contrib/sjws_nettask-standalone.yaml", NodeSettings.Mode.STANDALONE);
        assertMode("contrib/sjws_nettask-control.yaml", NodeSettings.Mode.CONTROL);
        assertMode("contrib/sjws_nettask-data.yaml", NodeSettings.Mode.DATA);
        var control = ConfigLoader.load(new String[] {"--config=contrib/sjws_nettask-control.yaml"}, Map.of());
        assertEquals(Boolean.FALSE, control.yamlBindContent());
        var data = ConfigLoader.load(new String[] {"--config=contrib/sjws_nettask-data.yaml"}, Map.of());
        assertNull(data.yamlBindContent());
    }

    private static void assertMode(String packaged, NodeSettings.Mode mode) {
        var path = Path.of(packaged);
        var loaded = ConfigLoader.load(new String[] {"--config=" + path}, Map.of());
        assertTrue(loaded.ok(), packaged + " " + loaded.errors());
        var document = loaded.document();
        assertEquals(ConfigDocument.SCHEMA, document.schemaVersion(), packaged);
        assertEquals(mode, document.node().mode(), packaged);
        assertEquals(path, document.configFile());
        var text = path.toString();
        assertFalse(read(path).contains("node.role"), text);
        var schemaErrors = ConfigValidator.validate(document).stream()
                .filter(error -> !error.startsWith("Document root "))
                .toList();
        assertTrue(schemaErrors.isEmpty(), packaged + " " + schemaErrors);
    }

    private static String read(Path path) {
        try {
            return java.nio.file.Files.readString(path);
        } catch (java.io.IOException e) {
            throw new AssertionError(path.toString(), e);
        }
    }
}
