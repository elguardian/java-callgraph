package gr.gousiosg.javacg.mcp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import gr.gousiosg.javacg.tool.Tool;
import gr.gousiosg.javacg.tool.ToolContext;
import gr.gousiosg.javacg.tool.ToolRegistry;

/** The server's tools: the providers on the class path plus the tools still bundled in this module. */
final class JavacgTools {

    private final ToolRegistry registry;

    JavacgTools() {
        this(new Models());
    }

    JavacgTools(Models models) {
        registry = ToolRegistry.load(JavacgTools.class.getClassLoader(), ToolContext.standard(), Set.of(),
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
