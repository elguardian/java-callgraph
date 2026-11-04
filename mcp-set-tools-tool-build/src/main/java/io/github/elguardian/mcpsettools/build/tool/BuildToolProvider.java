package io.github.elguardian.mcpsettools.build.tool;

import java.util.ArrayList;
import java.util.List;

import io.github.elguardian.mcpsettools.tool.Tool;
import io.github.elguardian.mcpsettools.tool.ToolContext;
import io.github.elguardian.mcpsettools.tool.ToolProvider;

/** The tools of mcp-set-tools-tool-build. */
public final class BuildToolProvider implements ToolProvider {

    @Override
    public String id() {
        return "build";
    }

    @Override
    public List<Tool> tools(ToolContext context) {
        List<Tool> all = new ArrayList<>();
        all.addAll(new MavenTools().tools());
        all.addAll(new JunitTools().tools());
        return all;
    }
}
