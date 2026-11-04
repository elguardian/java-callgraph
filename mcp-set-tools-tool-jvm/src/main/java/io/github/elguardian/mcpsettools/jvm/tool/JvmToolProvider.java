package io.github.elguardian.mcpsettools.jvm.tool;

import java.util.ArrayList;
import java.util.List;

import io.github.elguardian.mcpsettools.tool.Tool;
import io.github.elguardian.mcpsettools.tool.ToolContext;
import io.github.elguardian.mcpsettools.tool.ToolProvider;

/** The tools of mcp-set-tools-tool-jvm. */
public final class JvmToolProvider implements ToolProvider {

    @Override
    public String id() {
        return "jvm";
    }

    @Override
    public List<Tool> tools(ToolContext context) {
        List<Tool> all = new ArrayList<>();
        all.addAll(new LiveTools().tools());
        all.addAll(new WatchTools(context.jobs()).tools());
        all.addAll(new ThreadTools().tools());
        all.addAll(new GcTools().tools());
        all.addAll(new JfrTools(context.jobs()).tools());
        return all;
    }
}
