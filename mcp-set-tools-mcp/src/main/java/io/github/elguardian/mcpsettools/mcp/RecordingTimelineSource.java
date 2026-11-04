package io.github.elguardian.mcpsettools.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.github.elguardian.mcpsettools.analyze.MethodPattern;
import io.github.elguardian.mcpsettools.devtools.timeline.RecordingSource;
import io.github.elguardian.mcpsettools.timeline.Timeline;
import io.github.elguardian.mcpsettools.timeline.TimelineSource;
import io.github.elguardian.mcpsettools.tool.Schema;
import io.github.elguardian.mcpsettools.tool.ToolArgs;
import io.github.elguardian.mcpsettools.tool.ToolException;

/** Timeline source "recording": one block per call of the matching methods, lock waits and holds. */
public final class RecordingTimelineSource implements TimelineSource {

    @Override
    public String name() {
        return "recording";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public void arguments(Schema s) {
        s.strings("trace", "recording: recording directories or an agent out= directory", false)
                .string("methods", "recording: methods drawn. " + AnalysisTools.PATTERN_HELP + " Default: all", false)
                .bool("locks", "recording: draw lock waits and holds", true)
                .raw("min_duration_ms", "recording: leave out calls shorter than this", false, Map.of("type", "number", "default", 0));
    }

    @Override
    public Timeline read(ToolArgs args, Request r) throws Exception {
        List<Path> trace = args.paths("trace");
        if (trace.isEmpty()) {
            throw new ToolException("source=recording needs 'trace'");
        }
        MethodPattern methods = args.has("methods") ? Models.pattern(args.require("methods")) : null;
        long min = args.raw("min_duration_ms") instanceof Number n ? Math.round(n.doubleValue() * 1000) : 0;
        return RecordingSource.read(trace, methods, args.bool("locks", true), min, r.maxSpans());
    }
}
