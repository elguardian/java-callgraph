package gr.gousiosg.javacg.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import gr.gousiosg.javacg.devtools.stack.StackTrace;
import gr.gousiosg.javacg.devtools.stack.StackTraceParser;
import gr.gousiosg.javacg.devtools.stack.StackTraces;
import gr.gousiosg.javacg.devtools.table.Table;

/** Formatting tools: tables for chat/Jira/Slack, stack trace parsing. */
final class TableTools {

    List<ToolDef> tools() {
        return List.of(tableRender(), stacktraceParse());
    }

    private ToolDef tableRender() {
        return new ToolDef("table_render", "Render a table", """
                Renders rows as a table to paste somewhere: ascii (terminal, plain text), markdown (GitHub, \
                GitLab), jira (Jira/Confluence wiki markup), slack (a code block, since Slack has no \
                tables), csv, tsv or html. Give columns + rows (arrays of cells, or objects keyed by \
                column), or csv text with a header line. Numeric columns are right-aligned unless 'align' \
                says otherwise. Returns the table text only.""", true,
                Schema.object()
                        .choice("format", "Output format", "ascii", Schema.TABLE_FORMATS)
                        .strings("columns", "Column headers (default: the keys of object rows, or the CSV header)",
                                false)
                        .raw("rows", "Rows: arrays of cells, or objects keyed by column name", false,
                                Map.of("type", "array", "items", Map.of("type", List.of("array", "object"))))
                        .string("csv", "CSV (or TSV with separator=tab) text whose first line is the header", false)
                        .choice("separator", "Separator of the csv text", "comma", "comma", "tab", "semicolon")
                        .strings("align", "Per column: left, right or center (default: numbers right)", false)
                        .string("title", "Caption above the table", false)
                        .integer("max_width", "Longest cell in characters, longer ones are cut (0: no limit)", 0)
                        .build(),
                args -> {
                    Table t = build(args);
                    List<String> align = args.strings("align");
                    for (int i = 0; i < align.size() && i < t.columns().size(); i++) {
                        try {
                            t.align(i, Table.Align.valueOf(align.get(i).toUpperCase(Locale.ROOT)));
                        } catch (IllegalArgumentException e) {
                            throw new ToolException("'align' values are left, right or center, got " + align.get(i));
                        }
                    }
                    if (args.has("title")) {
                        t.title(args.require("title"));
                    }
                    return t.maxWidth(args.integer("max_width", 0))
                            .render(Table.Format.parse(args.string("format", "ascii")));
                });
    }

    @SuppressWarnings("unchecked")
    private static Table build(ToolArgs args) {
        List<String> columns = args.strings("columns");
        try {
            if (args.has("csv")) {
                char sep = switch (args.string("separator", "comma")) {
                    case "tab" -> '\t';
                    case "semicolon" -> ';';
                    default -> ',';
                };
                return Table.fromCsv(args.require("csv"), sep);
            }
            if (!(args.raw("rows") instanceof List<?> rows)) {
                throw new ToolException("Give 'rows' (with 'columns' for array rows) or 'csv'");
            }
            if (!rows.isEmpty() && rows.stream().allMatch(r -> r instanceof Map)) {
                return Table.fromMaps((List<Map<String, Object>>) rows, columns);
            }
            if (columns.isEmpty()) {
                throw new ToolException("Array rows need 'columns'");
            }
            Table t = Table.of(columns);
            for (Object r : rows) {
                if (r instanceof List<?> cells) {
                    t.row(cells);
                } else if (r instanceof Map<?, ?> m) {
                    List<Object> cells = new ArrayList<>();
                    columns.forEach(c -> cells.add(m.get(c)));
                    t.row(cells);
                } else {
                    throw new ToolException("Each row must be an array or an object, got " + r);
                }
            }
            return t;
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
    }

    private ToolDef stacktraceParse() {
        return new ToolDef("stacktrace_parse", "Parse a stack trace", """
                Parses Java stack trace text (printStackTrace output, a log excerpt, a test failure) into \
                its exception, message, cause chain and root cause, the first application frame, a \
                signature to tell whether two failures are the same (exception types + application \
                frames, no line numbers or message values), and a trimmed version with test-runner, \
                reflection and container frames collapsed.""", true,
                Schema.object().string("text", "Text containing one or more stack traces", true)
                        .strings("app_packages", "Package prefixes of the application, e.g. com.acme; frames "
                                + "elsewhere are library code (default: everything not JDK or framework)", false)
                        .bool("all", "Parse every stack trace in the text, not only the first", false)
                        .bool("trim", "Return the trimmed text", true).build(),
                args -> {
                    String text = args.require("text");
                    List<String> app = args.strings("app_packages");
                    boolean trim = args.bool("trim", true);
                    if (args.bool("all", false)) {
                        List<StackTrace> all = StackTraceParser.parseAll(text);
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("count", all.size());
                        m.put("traces", all.stream().map(t -> view(t, app, trim)).toList());
                        return m;
                    }
                    StackTrace t = StackTraceParser.parse(text)
                            .orElseThrow(() -> new ToolException("No stack trace found in the text"));
                    return view(t, app, trim);
                });
    }

    static Map<String, Object> view(StackTrace t, List<String> app, boolean trimmed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exception", t.exception());
        if (t.message() != null) {
            m.put("message", t.message());
        }
        StackTrace root = t.root();
        if (root != t) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("exception", root.exception());
            if (root.message() != null) {
                r.put("message", root.message());
            }
            m.put("root_cause", r);
            m.put("chain", t.chain().stream().map(StackTrace::exception).toList());
        }
        StackTrace.Frame f = StackTraces.firstAppFrame(t, app);
        if (f != null) {
            m.put("first_app_frame", f.toString());
        }
        String signature = StackTraces.signature(t, app, 3);
        m.put("signature", signature);
        m.put("signature_id", StackTraces.signatureId(signature));
        m.put("frames", t.chain().stream().mapToInt(s -> s.frames().size()).sum());
        if (trimmed) {
            m.put("trimmed", StackTraces.render(t, true, app));
        }
        return m;
    }
}
