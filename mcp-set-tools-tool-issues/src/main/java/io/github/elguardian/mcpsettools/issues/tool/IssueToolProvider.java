package io.github.elguardian.mcpsettools.issues.tool;

import java.util.ArrayList;
import java.util.List;

import io.github.elguardian.mcpsettools.tool.Tool;
import io.github.elguardian.mcpsettools.tool.ToolContext;
import io.github.elguardian.mcpsettools.tool.ToolProvider;

/** The tools of mcp-set-tools-tool-issues. */
public final class IssueToolProvider implements ToolProvider {

    @Override
    public String id() {
        return "issues";
    }

    @Override
    public List<Tool> tools(ToolContext context) {
        List<Tool> all = new ArrayList<>();
        all.addAll(new IssueTools().tools());
        return all;
    }

    @Override
    public String instructions() {
        return """
                Known issues: known_issue_match finds known issues (local store and Jira) matching a stack trace, log \
                or test failure; known_issue_add and known_issue_import fill the store.""";
    }
}
