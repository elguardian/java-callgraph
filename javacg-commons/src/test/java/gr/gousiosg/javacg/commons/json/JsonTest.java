package gr.gousiosg.javacg.commons.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void roundTrip() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "a \"q\" \\ \n é \u0001");
        m.put("n", 42L);
        m.put("d", 1.5);
        m.put("b", true);
        m.put("z", null);
        m.put("l", List.of(1L, "x", List.of(), Map.of()));
        String json = Json.write(m);
        assertEquals("{\"s\":\"a \\\"q\\\" \\\\ \\n é \\u0001\",\"n\":42,\"d\":1.5,\"b\":true,\"z\":null,\"l\":[1,\"x\",[],{}]}", json);
        assertEquals(m, Json.parse(json));
        assertEquals(List.of(-3L, 2.5e3, "\u00e9"), Json.parse(" [ -3 , 2.5e3, \"\\u00e9\" ] "));
    }

    @Test
    void navigationAndErrors() {
        Object o = Json.parse("{\"a\":{\"b\":[{\"c\":7}]},\"t\":true}");
        assertEquals(7L, Json.at(Json.list(o, "a", "b").get(0), "c"));
        assertEquals(true, Json.bool(o, "t"));
        assertEquals(0, Json.number(o, "missing"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,2] x"));
    }
}
