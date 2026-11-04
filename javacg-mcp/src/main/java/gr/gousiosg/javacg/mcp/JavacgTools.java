package gr.gousiosg.javacg.mcp;

import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All tools of the server, callable by name without the MCP transport. */
final class JavacgTools {

    /** Result of a call: a JSON-serializable value, or an error message for the client. */
    record Result(Object value, String error) {
        boolean isError() {
            return error != null;
        }
    }

    private final Map<String, ToolDef> tools = new LinkedHashMap<>();

    JavacgTools() {
        this(new Models());
    }

    JavacgTools(Models models) {
        Jobs jobs = new Jobs();
        List<ToolDef> all = new ArrayList<>(new AnalysisTools(models).tools());
        all.addAll(new LiveTools().tools());
        all.addAll(new TableTools().tools());
        all.addAll(new JunitTools().tools());
        all.addAll(new LogTools().tools());
        all.addAll(new MavenTools().tools());
        all.addAll(new TimelineTools().tools());
        all.addAll(jobs.tools());
        all.addAll(new ThreadTools().tools());
        all.addAll(new GcTools().tools());
        all.addAll(new JfrTools(jobs).tools());
        all.addAll(new GitTools(jobs).tools());
        all.addAll(new IssueTools().tools());
        all.addAll(new WatchTools(jobs).tools());
        all.forEach(t -> tools.put(t.name(), t));
    }

    List<ToolDef> all() {
        return List.copyOf(tools.values());
    }

    Result call(String name, Map<String, Object> arguments) {
        ToolDef tool = tools.get(name);
        if (tool == null) {
            return new Result(null, "Unknown tool " + name);
        }
        try {
            return new Result(tool.handler().call(new ToolArgs(arguments)), null);
        } catch (ToolException e) {
            return new Result(null, e.getMessage());
        } catch (NoSuchFileException e) {
            return new Result(null, "Does not exist: " + e.getFile() + (e.getReason() == null ? "" : " (" + e.getReason() + ")"));
        } catch (Exception | LinkageError e) {
            return new Result(null, name + " failed: " + e);
        }
    }
}
