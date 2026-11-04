package io.github.elguardian.mcpsettools.tool;

import java.util.List;

/**
 * A module's tools, found through {@link java.util.ServiceLoader}: list the implementation in
 * {@code META-INF/services/io.github.elguardian.mcpsettools.tool.ToolProvider}.
 */
public interface ToolProvider {

    /** Short id, e.g. {@code jvm}; {@code provider:<id>} in the disabled list turns all its tools off. */
    String id();

    /** The tools, built once per host with the host's context. */
    List<Tool> tools(ToolContext context);

    /** A paragraph telling an AI client when to use these tools, or null. */
    default String instructions() {
        return null;
    }
}
