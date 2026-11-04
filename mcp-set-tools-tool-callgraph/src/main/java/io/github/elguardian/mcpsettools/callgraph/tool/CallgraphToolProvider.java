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

    @Override
    public String instructions() {
        return """
                Call graphs: a static call graph built from bytecode combined with runtime recordings made by the \
                mcp-set-tools agent. load_model with the application jars/WAR as app (third-party jars as lib) and/or \
                recording directories as trace; find_methods to get exact method names; then call_paths, check_guard, \
                callers/callees, describe_method, gap_report, hotspots or run_detectors on the returned model_id. To \
                record a running JVM: list_jvms, attach_agent with incl= patterns, recorder start/stop, then load_model \
                with the stopped recording's directory as trace. Method names in results can be passed back as patterns.""";
    }
}
