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

    @Override
    public String instructions() {
        return """
                Builds and tests: maven_reactor (modules, build order, what a change affects), maven_run (runs Maven, \
                returns compiler errors, failed tests and module status instead of the raw log), maven_log (parses an \
                existing log); junit_summary, junit_failures and junit_compare read surefire/failsafe/Gradle XML reports.""";
    }
}
