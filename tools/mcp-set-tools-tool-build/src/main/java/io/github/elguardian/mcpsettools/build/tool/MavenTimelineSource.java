package io.github.elguardian.mcpsettools.build.tool;

import java.nio.file.Files;
import java.nio.file.Path;

import io.github.elguardian.mcpsettools.build.maven.MavenLogParser;
import io.github.elguardian.mcpsettools.build.maven.MavenSource;
import io.github.elguardian.mcpsettools.timeline.Timeline;
import io.github.elguardian.mcpsettools.timeline.TimelineSource;
import io.github.elguardian.mcpsettools.tool.Schema;
import io.github.elguardian.mcpsettools.tool.ToolArgs;
import io.github.elguardian.mcpsettools.tool.ToolException;

/** Timeline source "maven": module builds per builder thread of a timestamped maven_run log. */
public final class MavenTimelineSource implements TimelineSource {

    @Override
    public String name() {
        return "maven";
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public void arguments(Schema s) {
        s.string("log_file", "maven: a log written by maven_run (it has timestamps and threads)", false);
    }

    @Override
    public Timeline read(ToolArgs args, Request r) throws Exception {
        Path log = args.has("log_file") ? ToolArgs.path(args.require("log_file")) : null;
        if (log == null || !Files.isRegularFile(log)) {
            throw new ToolException("source=maven needs 'log_file', the log_file of a maven_run result");
        }
        return MavenSource.read(MavenLogParser.parse(log));
    }
}
