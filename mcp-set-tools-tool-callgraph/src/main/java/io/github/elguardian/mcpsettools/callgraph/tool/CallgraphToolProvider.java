package io.github.elguardian.mcpsettools.callgraph.tool;

import java.util.ArrayList;
import java.util.List;

import io.github.elguardian.mcpsettools.tool.Tool;
import io.github.elguardian.mcpsettools.tool.ToolContext;
import io.github.elguardian.mcpsettools.tool.ToolProvider;

/** The tools of mcp-set-tools-tool-callgraph. */
public final class CallgraphToolProvider implements ToolProvider {

    @Override
    public String id() {
        return "callgraph";
    }

    @Override
    public List<Tool> tools(ToolContext context) {
        List<Tool> all = new ArrayList<>();
        all.addAll(new AnalysisTools(new Models()).tools());
        return all;
    }
}
