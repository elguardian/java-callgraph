package gr.gousiosg.javacg.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the JSON schema of a tool's arguments: a flat object of simple properties. */
final class Schema {

    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    private Schema() {
    }

    static Schema object() {
        return new Schema();
    }

    Schema string(String name, String description, boolean isRequired) {
        return property(name, isRequired, "type", "string", "description", description);
    }

    Schema strings(String name, String description, boolean isRequired) {
        return property(name, isRequired, "type", "array", "items", Map.of("type", "string"), "description",
                description);
    }

    Schema integer(String name, String description, int dflt) {
        return property(name, false, "type", "integer", "description", description, "default", dflt);
    }

    Schema bool(String name, String description, boolean dflt) {
        return property(name, false, "type", "boolean", "description", description, "default", dflt);
    }

    /** A string restricted to {@code values}; {@code dflt} null makes it required. */
    Schema choice(String name, String description, String dflt, String... values) {
        if (dflt == null) {
            return property(name, true, "type", "string", "enum", List.of(values), "description", description);
        }
        return property(name, false, "type", "string", "enum", List.of(values), "description", description,
                "default", dflt);
    }

    /** The {@code model_id} argument most tools take. */
    Schema model() {
        return string("model_id", "Id returned by load_model. May be omitted when exactly one model is loaded.",
                false);
    }

    /** The {@code edges} argument of the graph queries. */
    Schema edges() {
        return choice("edges", "Edges to follow: all, static (found by the static analysis) or dynamic "
                + "(observed at runtime)", "all", "all", "static", "dynamic");
    }

    Map<String, Object> build() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private Schema property(String name, boolean isRequired, Object... keyValues) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            p.put((String) keyValues[i], keyValues[i + 1]);
        }
        properties.put(name, p);
        if (isRequired) {
            required.add(name);
        }
        return this;
    }
}
