package io.github.elguardian.mcpsettools.timeline.tool;

import java.util.ArrayList;
import java.util.List;

import io.github.elguardian.mcpsettools.tool.Tool;
import io.github.elguardian.mcpsettools.tool.ToolContext;
import io.github.elguardian.mcpsettools.tool.ToolProvider;

/** The tools of mcp-set-tools-tool-timeline. */
public final class TimelineToolProvider implements ToolProvider {

    @Override
    public String id() {
        return "timeline";
    }

    @Override
    public List<Tool> tools(ToolContext context) {
        List<Tool> all = new ArrayList<>();
        all.addAll(new TimelineTools(context).tools());
        return all;
    }

    @Override
    public String instructions() {
        return """
                Concurrency: timeline draws what threads did over time from a recording, logs, a maven_run log or given \
                events, with overlap and lock-wait analysis.""";
    }
}
