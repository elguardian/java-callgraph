package io.github.elguardian.mcpsettools.mcp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.elguardian.mcpsettools.tool.Tool;
import io.github.elguardian.mcpsettools.tool.ToolContext;
import io.github.elguardian.mcpsettools.tool.ToolRegistry;

/** The server's tools: the providers on the class path plus the tools still bundled in this module. */
final class ServerTools {

    private final ToolRegistry registry;

    ServerTools() {
        this(new Models());
    }

    ServerTools(Models models) {
        registry = ToolRegistry.load(ServerTools.class.getClassLoader(), ToolContext.standard(), Set.of(),
                List.of(new BundledTools(models)));
    }

    ToolRegistry registry() {
        return registry;
    }

    List<Tool> all() {
        return registry.tools();
    }

    ToolRegistry.Result call(String name, Map<String, Object> arguments) {
        return registry.call(name, arguments);
    }
}
