package io.github.elguardian.mcpsettools.logs.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.elguardian.mcpsettools.logs.LogFormat;
import io.github.elguardian.mcpsettools.logs.LogReader;
import io.github.elguardian.mcpsettools.logs.LogSource;
import io.github.elguardian.mcpsettools.timeline.Timeline;
import io.github.elguardian.mcpsettools.timeline.TimelineSource;
import io.github.elguardian.mcpsettools.tool.Schema;
import io.github.elguardian.mcpsettools.tool.ToolArgs;
import io.github.elguardian.mcpsettools.tool.ToolException;

/** Timeline source "log": blocks opened and closed by span rules, markers as instants. */
public final class LogTimelineSource implements TimelineSource {

    @Override
    public String name() {
        return "log";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public void arguments(Schema s) {
        Map<String, Object> rule = Map.of("type", "object", "properties", Map.of(
                "name", Map.of("type", "string"),
                "start", Map.of("type", "string", "description", "Regex on the message opening the span"),
                "end", Map.of("type", "string", "description", "Regex on the message closing it"),
                "key", Map.of("type", "string", "description", "Named group in both regexes pairing start and end across "
                        + "threads (e.g. a request id); without it they pair on the same thread")),
                "required", List.of("name", "start", "end"));
        Map<String, Object> marker = Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"),
                "match", Map.of("type", "string", "description", "Regex on the message and its stack trace")),
                "required", List.of("name", "match"));
        s.strings("files", "log: log files (one process each)", false)
                .string("pattern", "log: " + LogTools.PATTERN_HELP + ". Default auto", false)
                .string("date_format", "log: timestamp format overriding the pattern's", false)
                .raw("spans", "log: span rules", false, Map.of("type", "array", "items", rule))
                .raw("markers", "log: records drawn as instants", false, Map.of("type", "array", "items", marker));
    }

    @Override
    public Timeline read(ToolArgs args, Request r) throws Exception {
        List<Path> files = args.paths("files");
        if (files.isEmpty()) {
            throw new ToolException("source=log needs 'files'");
        }
        for (Path f : files) {
            if (!Files.isRegularFile(f)) {
                throw new ToolException("Not a file: " + f);
            }
        }
        String spec = args.string("pattern", "auto");
        if (spec.equals("auto")) {
            spec = LogFormat.detect(LogReader.head(files.get(0), 200)).orElseThrow(() -> new ToolException(
                    "Could not recognize the log format of " + files.get(0) + ": pass 'pattern'. " + LogTools.PATTERN_HELP)).name();
        }
        LogFormat format;
        try {
            format = LogFormat.of(spec, args.string("date_format", null), r.zone().getId());
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
        List<LogSource.Rule> rules = new ArrayList<>();
        for (Map<String, Object> rule : args.objects("spans")) {
            rules.add(new LogSource.Rule(ToolArgs.field(rule, "name"), ToolArgs.regex(ToolArgs.field(rule, "start"), "spans.start"),
                    ToolArgs.regex(ToolArgs.field(rule, "end"), "spans.end"), rule.get("key") == null ? null : ToolArgs.field(rule, "key")));
        }
        List<LogSource.Marker> markers = new ArrayList<>();
        for (Map<String, Object> mk : args.objects("markers")) {
            markers.add(new LogSource.Marker(ToolArgs.field(mk, "name"), ToolArgs.regex(ToolArgs.field(mk, "match"), "markers.match")));
        }
        if (rules.isEmpty() && markers.isEmpty()) {
            throw new ToolException("source=log needs 'spans' rules and/or 'markers', e.g. spans: [{name: "
                    + "\"request\", start: \"begin request (?<id>\\\\w+)\", end: \"end request (?<id>\\\\w+)\", key: \"id\"}]");
        }
        try {
            return LogSource.read(files, format, rules, markers);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
    }
}
