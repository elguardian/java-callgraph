package io.github.elguardian.mcpsettools.status.tool;

import java.util.ArrayList;
import java.util.List;

import io.github.elguardian.mcpsettools.tool.Tool;
import io.github.elguardian.mcpsettools.tool.ToolContext;
import io.github.elguardian.mcpsettools.tool.ToolProvider;

/** The tools of mcp-set-tools-tool-status. */
public final class StatusToolProvider implements ToolProvider {

    @Override
    public String id() {
        return "status";
    }

    @Override
    public List<Tool> tools(ToolContext context) {
        List<Tool> all = new ArrayList<>();
        all.addAll(new StatusTools().tools());
        return all;
    }
}
