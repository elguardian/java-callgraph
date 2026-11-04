package io.github.elguardian.mcpsettools.mcp;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.elguardian.mcpsettools.timeline.Span;
import io.github.elguardian.mcpsettools.timeline.Timeline;
import io.github.elguardian.mcpsettools.timeline.TimelineSource;
import io.github.elguardian.mcpsettools.tool.Schema;
import io.github.elguardian.mcpsettools.tool.ToolArgs;
import io.github.elguardian.mcpsettools.tool.ToolException;

/** Timeline source "events": blocks given by the caller. */
public final class EventsTimelineSource implements TimelineSource {

    @Override
    public String name() {
        return "events";
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public void arguments(Schema s) {
        Map<String, Object> event = Map.of("type", "object", "properties", Map.of(
                "thread", Map.of("type", "string"), "label", Map.of("type", "string"),
                "start", Map.of("type", List.of("number", "string"), "description", "Milliseconds or ISO time"),
                "end", Map.of("type", List.of("number", "string"), "description", "Milliseconds or ISO time"),
                "category", Map.of("type", "string"), "process", Map.of("type", "string")),
                "required", List.of("thread", "start", "end", "label"));
        s.raw("events", "events: the blocks", false, Map.of("type", "array", "items", event));
    }

    @Override
    public Timeline read(ToolArgs args, Request r) {
        List<Span> spans = new ArrayList<>();
        for (Map<String, Object> e : args.objects("events")) {
            long start = time(e.get("start"), r.zone());
            long end = time(e.get("end"), r.zone());
            spans.add(new Span(e.get("process") == null ? "events" : ToolArgs.field(e, "process"), ToolArgs.field(e, "thread"), start,
                    end, ToolArgs.field(e, "label"), e.get("category") == null ? "span" : ToolArgs.field(e, "category"), 0, Map.of()));
        }
        if (spans.isEmpty()) {
            throw new ToolException("source=events needs 'events'");
        }
        return new Timeline(spans, List.of(), List.of());
    }

    /** Milliseconds (epoch or relative) as a number, or an ISO date-time; returns microseconds. */
    private static long time(Object v, ZoneId zone) {
        if (v instanceof Number n) {
            return Math.round(n.doubleValue() * 1000);
        }
        String s = String.valueOf(v).strip().replace(' ', 'T');
        try {
            Instant i;
            try {
                i = OffsetDateTime.parse(s).toInstant();
            } catch (DateTimeParseException e) {
                i = LocalDateTime.parse(s).atZone(zone).toInstant();
            }
            return i.getEpochSecond() * 1_000_000 + i.getNano() / 1000;
        } catch (DateTimeParseException e) {
            throw new ToolException("Event time '" + v + "' is neither milliseconds nor an ISO date-time");
        }
    }
}
