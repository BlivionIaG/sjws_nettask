package sjws_nettask.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class MiniJsonTest {

    @Test
    void readsAJwtObject() {
        var map = MiniJson.object(" { \"alg\" : \"HS256\", \"n\" : -2, \"ok\" : true, \"no\" : false, \"z\" : null, "
                + "\"aud\" : [\"a\", \"b\"], \"esc\" : \"a\\nb\\u0041\" } ");
        assertEquals("HS256", map.get("alg"));
        assertEquals(-2L, map.get("n"));
        assertEquals(Boolean.TRUE, map.get("ok"));
        assertEquals(Boolean.FALSE, map.get("no"));
        assertNull(map.get("z"));
        assertEquals(List.of("a", "b"), map.get("aud"));
        assertEquals("a\nbA", map.get("esc"));
        assertEquals(0, MiniJson.object("{}").size());
    }

    @Test
    void rejectsLooseJson() {
        assertThrows(IllegalArgumentException.class, () -> MiniJson.object(null));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.object("[]"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.object("{\"a\":1} trailing"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.object("{\"a\":1,\"a\":2}"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.object("{\"a\":01}"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.object("{\"a\":1.5}"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.object("{\"a\":\"\\q\"}"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.object("{\"a\":\"unterminated}"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.object("{\"a\":99999999999999999999}"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.object(nested(9)));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.object(""));
    }

    private static String nested(int depth) {
        return "{\"a\":".repeat(depth) + "1" + "}".repeat(depth);
    }
}
