package io.github.elguardian.mcpsettools.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.elguardian.mcpsettools.commons.json.Json;

/**
 * {@code <home>/tools.json}: {@code {"disabled": ["maven_run", "provider:status"]}} hides tools by
 * name, or every tool of a provider by its id.
 */
record ToolsConfig(List<String> disabled) {

    static final ToolsConfig NONE = new ToolsConfig(List.of());

    static ToolsConfig read(Path file, List<String> warnings) {
        if (!Files.isRegularFile(file)) {
            return NONE;
        }
        try {
            if (!(Json.parse(Files.readString(file)) instanceof Map<?, ?> m)) {
                warnings.add(file + " ignored: not a JSON object");
                return NONE;
            }
            List<String> disabled = new ArrayList<>();
            if (m.get("disabled") instanceof List<?> l) {
                l.forEach(x -> disabled.add(String.valueOf(x)));
            } else if (m.containsKey("disabled")) {
                warnings.add(file + ": \"disabled\" must be a list of tool names");
            }
            return new ToolsConfig(List.copyOf(disabled));
        } catch (IOException | RuntimeException e) {
            warnings.add(file + " ignored: " + e.getMessage());
            return NONE;
        }
    }
}
