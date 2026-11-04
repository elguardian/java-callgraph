package io.github.elguardian.mcpsettools.mcp;

import java.nio.file.Files;
import java.nio.file.Path;

import io.github.elguardian.mcpsettools.devtools.timeline.JfrSource;
import io.github.elguardian.mcpsettools.timeline.Timeline;
import io.github.elguardian.mcpsettools.timeline.TimelineSource;
import io.github.elguardian.mcpsettools.tool.Schema;
import io.github.elguardian.mcpsettools.tool.ToolArgs;
import io.github.elguardian.mcpsettools.tool.ToolException;

/** Timeline source "jfr": monitor waits, parks, I/O and GC pauses of a JFR recording. */
public final class JfrTimelineSource implements TimelineSource {

    @Override
    public String name() {
        return "jfr";
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public void arguments(Schema s) {
        s.string("jfr_file", "jfr: a recording; monitor waits, parks, I/O and GC pauses become blocks", false);
    }

    @Override
    public Timeline read(ToolArgs args, Request r) throws Exception {
        Path f = args.has("jfr_file") ? ToolArgs.path(args.require("jfr_file")) : null;
        if (f == null || !Files.isRegularFile(f)) {
            throw new ToolException("source=jfr needs 'jfr_file', a .jfr recording");
        }
        return JfrSource.read(f, r.maxSpans());
    }
}
