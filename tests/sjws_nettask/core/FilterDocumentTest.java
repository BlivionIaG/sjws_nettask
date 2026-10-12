package sjws_nettask.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Wire format, scope and label selectors for a declarative filter document. */
class FilterDocumentTest {

    @Test
    void canonicalJsonRoundTrips() {
        var doc = FilterDocument.of(7, List.of(
                FilterDocument.FilterEntry.of("api-key", 10, Map.of("keys", "a,b", "header", "x-api-key"),
                        FilterScope.of("/api/*", "edge,!internal")),
                FilterDocument.FilterEntry.of("cors", -1, Map.of("origins", "https://app.example"),
                        FilterScope.route("/")),
                FilterDocument.FilterEntry.of("mark")));
        var json = doc.toJson();
        assertTrue(json.startsWith("{\"format\":1,\"version\":7,\"filters\":["));
        assertTrue(json.indexOf("\"header\"") < json.indexOf("\"keys\""));
        assertEquals(doc, FilterDocument.fromJson(json));
        assertEquals(json, FilterDocument.fromJson(json).toJson());
        assertEquals(doc, FilterDocument.fromJson(doc.toJsonBytes()));
    }

    @Test
    void looseInputNormalizesToTheCanonicalDocument() {
        var loose = """
                {
                  "version": 0,
                  "format": 1,
                  "filters": [
                    {
                      "params": { "b": "2", "a": "line\\n\\"quote\\"" },
                      "type": "  mark  ",
                      "scope": { "labels": " * ", "route": " * " }
                    },
                    {
                      "type": "size",
                      "order": 4,
                      "scope": { "labels": " edge , !internal " }
                    }
                  ]
                }
                """;
        var doc = FilterDocument.fromJson(loose);
        assertEquals(0, doc.version());
        assertEquals("mark", doc.filters().get(0).type());
        assertEquals(0, doc.filters().get(0).order());
        assertNull(doc.filters().get(0).scope().route());
        assertNull(doc.filters().get(0).scope().labels());
        assertEquals("line\n\"quote\"", doc.filters().get(0).params().get("a"));
        assertEquals("edge,!internal", doc.filters().get(1).scope().labels());
        assertNull(doc.filters().get(1).scope().route());
        assertEquals(Map.of(), doc.filters().get(1).params());
        assertEquals(doc, FilterDocument.fromJson(doc.toJson()));
    }

    @Test
    void escapesAndUtf8() {
        var doc = FilterDocument.of(1, List.of(FilterDocument.FilterEntry.of("t", 0,
                Map.of("v", "a\u0001b\t/\u00e9"), FilterScope.all())));
        var again = FilterDocument.fromJson(doc.toJsonBytes());
        assertEquals("a\u0001b\t/\u00e9", again.filters().get(0).params().get("v"));
        assertEquals(doc, again);
        var slash = FilterDocument.fromJson("{\"format\":1,\"version\":1,\"filters\":[{\"type\":\"t\",\"params\":{\"v\":\"a\\/b\\b\\f\"}}]}");
        assertEquals("a/b\b\f", slash.filters().get(0).params().get("v"));
        var letter = FilterDocument.fromJson("{\"format\":1,\"version\":1,\"filters\":[{\"type\":\"\\u0041\"}]}");
        assertEquals("A", letter.filters().get(0).type());
    }

    @Test
    void emptyDocumentIsValid() {
        var doc = FilterDocument.empty(0);
        assertEquals(FilterDocument.FORMAT, doc.format());
        assertTrue(doc.filters().isEmpty());
        assertEquals(doc, FilterDocument.fromJson(doc.toJson()));
    }

    @Test
    void rejectedDocuments() {
        assertThrows(IllegalArgumentException.class, () -> new FilterDocument(2, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> FilterDocument.empty(-1));
        assertThrows(IllegalArgumentException.class, () -> FilterDocument.FilterEntry.of("  "));
        assertThrows(IllegalArgumentException.class, () -> FilterDocument.FilterEntry.of("t", 0, Map.of("", "x"), null));
        var nullValue = new HashMap<String, String>();
        nullValue.put("k", null);
        assertThrows(IllegalArgumentException.class, () -> new FilterDocument.FilterEntry("t", 0, nullValue, null));
        assertThrows(NullPointerException.class, () -> new FilterDocument(1, 0, null));
        assertThrows(IllegalArgumentException.class, () -> FilterDocument.fromJson((String) null));
        assertThrows(IllegalArgumentException.class, () -> FilterDocument.fromJson((byte[]) null));
        assertThrows(IllegalArgumentException.class,
                () -> FilterDocument.fromJson("x".repeat(FilterDocument.MAX_BYTES + 1)));
        var huge = new byte[FilterDocument.MAX_BYTES + 1];
        assertThrows(IllegalArgumentException.class, () -> FilterDocument.fromJson(huge));
        assertThrows(IllegalArgumentException.class, () -> FilterDocument.fromJson(new byte[] { (byte) 0xff }));
        for (var bad : List.of(
                "",
                "[]",
                "1",
                "\"s\"",
                "true",
                "null",
                "{\"format\":1}",
                "{\"format\":1,\"version\":0}",
                "{\"format\":1,\"version\":0,\"filters\":[],\"extra\":1}",
                "{\"format\":1,\"version\":0,\"filters\":null}",
                "{\"format\":\"1\",\"version\":0,\"filters\":[]}",
                "{\"format\":1,\"version\":-1,\"filters\":[]}",
                "{\"format\":1.0,\"version\":0,\"filters\":[]}",
                "{\"format\":01,\"version\":0,\"filters\":[]}",
                "{\"format\":1,\"version\":0,\"filters\":[1]}",
                "{\"format\":1,\"version\":0,\"filters\":[{}]}",
                "{\"format\":1,\"version\":0,\"filters\":[{\"type\":1}]}",
                "{\"format\":1,\"version\":0,\"filters\":[{\"type\":\"t\",\"order\":\"0\"}]}",
                "{\"format\":1,\"version\":0,\"filters\":[{\"type\":\"t\",\"order\":9999999999}]}",
                "{\"format\":1,\"version\":0,\"filters\":[{\"type\":\"t\",\"params\":[]}]}",
                "{\"format\":1,\"version\":0,\"filters\":[{\"type\":\"t\",\"params\":{\"n\":1}}]}",
                "{\"format\":1,\"version\":0,\"filters\":[{\"type\":\"t\",\"params\":{\"a\":\"b\",\"a\":\"c\"}}]}",
                "{\"format\":1,\"version\":0,\"filters\":[{\"type\":\"t\",\"scope\":{}}]}",
                "{\"format\":1,\"version\":0,\"filters\":[{\"type\":\"t\",\"scope\":{\"other\":\"x\"}}]}",
                "{\"format\":1,\"version\":0,\"filters\":[{\"type\":\"t\",\"scope\":{\"route\":1}}]}",
                "{\"format\":1,\"version\":0,\"filters\":[{\"type\":\"t\",\"scope\":{\"labels\":1}}]}",
                "{\"format\":1,\"version\":0,\"filters\":[{\"type\":\"t\",\"scope\":{\"route\":\"**\"}}]}",
                "{\"format\":1,\"version\":0,\"filters\":[{\"type\":\"t\",\"scope\":{\"labels\":\"bad label\"}}]}",
                "{\"format\":1,\"version\":0,\"filters\":[{\"type\":\"t\",}]}",
                "{\"format\":1,\"version\":0,\"filters\":[}",
                "{\"format\":1,\"version\":0,\"filters\":[]",
                "{\"format\":1,\"version\":0,\"filters\":[]} trailing",
                "{\"format\":1,\"version\":9223372036854775808,\"filters\":[]}",
                "{\"format\":1,\"version\":0,\"filters\":[{\"type\":\"t\",\"params\":{\"v\":\"\\q\"}}]}",
                "{\"format\":1,\"version\":0,\"filters\":[{\"type\":\"unterminated}]}",
                "{\"format\":1,\"version\":0,\"filters\":[{\"type\":\"a\u0001\"}]}",
                "{\"format\":1,\"version\":0,\"filters\":[{\"type\":\"\\u12\"}]}",
                "{\"format\":1,\"version\":0,\"filters\":[{\"type\":\"\\uXXXX\"}]}",
                "{\"format\":1,\"version\":-",
                "-")) {
            assertThrows(IllegalArgumentException.class, () -> FilterDocument.fromJson(bad), bad);
        }
    }

    @Test
    void labelSelectorMatchesAtCompileTime() {
        assertNull(LabelSelector.parse(null).canonical());
        assertNull(LabelSelector.parse("  ").canonical());
        assertNull(LabelSelector.parse("*").canonical());
        assertTrue(LabelSelector.parse("*").matches(Set.of()));
        assertTrue(LabelSelector.parse(null).matches(null));
        var selector = LabelSelector.parse(" edge , api , !internal ");
        assertEquals("edge,api,!internal", selector.canonical());
        assertTrue(selector.matches(Set.of("edge", "api")));
        assertTrue(selector.matches(Set.of("edge", "api", "other")));
        assertFalse(selector.matches(Set.of("edge")));
        assertFalse(selector.matches(Set.of("edge", "api", "internal")));
        assertFalse(selector.matches(null));
        assertThrows(IllegalArgumentException.class, () -> LabelSelector.parse("edge,,api"));
        assertThrows(IllegalArgumentException.class, () -> LabelSelector.parse("!"));
        assertThrows(IllegalArgumentException.class, () -> LabelSelector.parse("!!edge"));
        assertThrows(IllegalArgumentException.class, () -> LabelSelector.parse("has space"));
        assertThrows(IllegalArgumentException.class, () -> FilterScope.route("nope"));
        assertThrows(IllegalArgumentException.class, () -> FilterScope.labels("bad label"));
        assertNull(FilterScope.route("  *  ").route());
        assertNull(FilterScope.route("   ").route());
        assertEquals("/api/*", FilterScope.route(" /api/* ").route());
    }
}
