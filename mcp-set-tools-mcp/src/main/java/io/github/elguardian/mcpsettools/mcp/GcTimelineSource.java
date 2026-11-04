package io.github.elguardian.mcpsettools.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.elguardian.mcpsettools.devtools.gc.GcEvent;
import io.github.elguardian.mcpsettools.devtools.gc.GcLog;
import io.github.elguardian.mcpsettools.timeline.Span;
import io.github.elguardian.mcpsettools.timeline.Timeline;
import io.github.elguardian.mcpsettools.timeline.TimelineSource;
import io.github.elguardian.mcpsettools.tool.Schema;
import io.github.elguardian.mcpsettools.tool.ToolArgs;
import io.github.elguardian.mcpsettools.tool.ToolException;

/** Timeline source "gc": pauses (and concurrent cycles on their own lane) at wall-clock time when logged, else at uptime. */
public final class GcTimelineSource implements TimelineSource {

    @Override
    public String name() {
        return "gc";
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public void arguments(Schema s) {
        s.strings("gc_files", "gc: GC log files; pauses become blocks on a GC lane", false);
    }

    @Override
    public Timeline read(ToolArgs args, Request r) throws Exception {
        List<Path> files = args.paths("gc_files");
        if (files.isEmpty()) {
            throw new ToolException("source=gc needs 'gc_files'");
        }
        Map<String, Object> pass = new LinkedHashMap<>();
        pass.put("files", files.stream().map(Path::toString).toList());
        GcLog log = GcTools.read(new ToolArgs(pass));
        List<Span> spans = new ArrayList<>();
        for (GcEvent e : log.events()) {
            long start = e.wall() != Long.MIN_VALUE ? e.wall() : e.uptime() >= 0 ? Math.round(e.uptime() * 1_000_000) : -1;
            if (start < 0) {
                continue;
            }
            Map<String, String> a = new LinkedHashMap<>();
            if (!e.detail().isEmpty()) {
                a.put("cause", e.detail());
            }
            if (e.before() >= 0) {
                a.put("heap_kb", e.before() + " -> " + e.after());
            }
            spans.add(new Span("gc", e.pause() ? "GC pauses" : "GC concurrent", start, start + Math.round(e.millis() * 1000),
                    e.kind(), e.pause() ? "gc-pause" : "gc-concurrent", 0, a));
        }
        return new Timeline(spans, List.of(), List.of());
    }
}
