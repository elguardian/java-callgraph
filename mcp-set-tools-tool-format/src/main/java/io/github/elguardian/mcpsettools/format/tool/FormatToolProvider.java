package io.github.elguardian.mcpsettools.format.tool;

import java.util.ArrayList;
import java.util.List;

import io.github.elguardian.mcpsettools.tool.Tool;
import io.github.elguardian.mcpsettools.tool.ToolContext;
import io.github.elguardian.mcpsettools.tool.ToolProvider;

/** The tools of mcp-set-tools-tool-format. */
public final class FormatToolProvider implements ToolProvider {

    @Override
    public String id() {
        return "format";
    }

    @Override
    public List<Tool> tools(ToolContext context) {
        List<Tool> all = new ArrayList<>();
        all.addAll(new TableTools().tools());
        return all;
    }
}
