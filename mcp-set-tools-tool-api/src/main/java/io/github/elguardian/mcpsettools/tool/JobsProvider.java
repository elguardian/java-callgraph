package io.github.elguardian.mcpsettools.tool;

import java.util.List;

/** job_status, job_list and job_cancel for the context's background jobs. */
public final class JobsProvider implements ToolProvider {

    @Override
    public String id() {
        return "jobs";
    }

    @Override
    public List<Tool> tools(ToolContext context) {
        return context.jobs().tools();
    }

    @Override
    public String instructions() {
        return "Long tools (bisect_run, and watch_method / jfr_analyze with background=true) return a job_id: poll job_status. "
                + "The server also sends a log message (notifications/message) for each job step and when the job ends.";
    }
}
