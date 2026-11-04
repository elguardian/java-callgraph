package io.github.elguardian.mcpsettools.git.tool;

import java.util.ArrayList;
import java.util.List;

import io.github.elguardian.mcpsettools.tool.Tool;
import io.github.elguardian.mcpsettools.tool.ToolContext;
import io.github.elguardian.mcpsettools.tool.ToolProvider;

/** The tools of mcp-set-tools-tool-git. */
public final class GitToolProvider implements ToolProvider {

    @Override
    public String id() {
        return "git";
    }

    @Override
    public List<Tool> tools(ToolContext context) {
        List<Tool> all = new ArrayList<>();
        all.addAll(new GitTools(context.jobs()).tools());
        return all;
    }
}
