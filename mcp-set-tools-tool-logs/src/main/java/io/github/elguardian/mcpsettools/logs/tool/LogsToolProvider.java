package io.github.elguardian.mcpsettools.logs.tool;

import java.util.ArrayList;
import java.util.List;

import io.github.elguardian.mcpsettools.tool.Tool;
import io.github.elguardian.mcpsettools.tool.ToolContext;
import io.github.elguardian.mcpsettools.tool.ToolProvider;

/** The tools of mcp-set-tools-tool-logs. */
public final class LogsToolProvider implements ToolProvider {

    @Override
    public String id() {
        return "logs";
    }

    @Override
    public List<Tool> tools(ToolContext context) {
        List<Tool> all = new ArrayList<>();
        all.addAll(new LogTools().tools());
        return all;
    }
}
