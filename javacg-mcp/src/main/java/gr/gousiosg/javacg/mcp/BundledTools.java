package gr.gousiosg.javacg.mcp;

import java.util.ArrayList;
import java.util.List;

import gr.gousiosg.javacg.tool.Jobs;
import gr.gousiosg.javacg.tool.Tool;
import gr.gousiosg.javacg.tool.ToolContext;
import gr.gousiosg.javacg.tool.ToolProvider;

/** The tools not yet moved to their own modules, as one provider. */
final class BundledTools implements ToolProvider {

    private final Models models;

    BundledTools(Models models) {
        this.models = models;
    }

    @Override
    public String id() {
        return "bundled";
    }

    @Override
    public List<Tool> tools(ToolContext context) {
        Jobs jobs = context.jobs();
        List<Tool> all = new ArrayList<>(new AnalysisTools(models).tools());
        all.addAll(new LiveTools().tools());
        all.addAll(new TableTools().tools());
        all.addAll(new JunitTools().tools());
        all.addAll(new LogTools().tools());
        all.addAll(new MavenTools().tools());
        all.addAll(new TimelineTools().tools());
        all.addAll(new ThreadTools().tools());
        all.addAll(new GcTools().tools());
        all.addAll(new JfrTools(jobs).tools());
        all.addAll(new GitTools(jobs).tools());
        all.addAll(new IssueTools().tools());
        all.addAll(new WatchTools(jobs).tools());
        all.addAll(new StatusTools().tools());
        return all;
    }
}
