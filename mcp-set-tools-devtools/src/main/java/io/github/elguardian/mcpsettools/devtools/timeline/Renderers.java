package io.github.elguardian.mcpsettools.devtools.timeline;

import java.io.StringWriter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import io.github.elguardian.mcpsettools.commons.json.JsonWriter;
import io.github.elguardian.mcpsettools.commons.table.Table;

/** Timeline diagrams: ASCII swimlanes, Mermaid gantt, PlantUML concise, Chrome trace JSON, HTML/SVG. */
public final class Renderers {

    public enum Format {
        ASCII, MERMAID, PLANTUML, CHROME, HTML
    }

    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private Renderers() {
    }

    public static String render(Timeline t, Format format, int width, int maxRows, ZoneId zone) {
        return switch (format) {
            case ASCII -> ascii(t, width, maxRows, zone);
            case MERMAID -> mermaid(t);
            case PLANTUML -> plantuml(t, maxRows);
            case CHROME -> chrome(t, zone);
            case HTML -> html(t, maxRows, zone);
        };
    }

    // ---- ASCII

    static String ascii(Timeline t, int width, int maxRows, ZoneId zone) {
        if (t.isEmpty()) {
            return "(empty timeline)\n";
        }
        long t0 = t.start();
        long range = Math.max(1, t.end() - t0);
        Layout layout = new Layout(t, maxRows);
        boolean multi = t.multiProcess();
        Map<String, Character> letters = letters(t);
        int labelWidth = 0;
        for (String lane : layout.lanes) {
            labelWidth = Math.max(labelWidth, laneName(lane, multi).length());
        }
        labelWidth = Math.min(Math.max(labelWidth, 6), 32);
        Map<String, char[][]> grid = new LinkedHashMap<>();
        Map<String, char[]> markRows = new LinkedHashMap<>();
        for (String lane : layout.lanes) {
            char[][] rows = new char[layout.rows(lane)][width];
            for (char[] r : rows) {
                java.util.Arrays.fill(r, ' ');
            }
            grid.put(lane, rows);
        }
        for (Span s : t.spans()) {
            int a = col(s.start(), t0, range, width);
            int b = Math.max(a, col(s.end(), t0, range, width) - (s.end() > s.start() ? 1 : 0));
            if (layout.row(s) < 0) {
                continue;
            }
            char c = symbol(s, letters);
            char[] row = grid.get(s.lane())[layout.row(s)];
            for (int i = a; i <= Math.min(b, width - 1); i++) {
                row[i] = c;
            }
        }
        for (Mark m : t.marks()) {
            char[] row = markRows.computeIfAbsent(m.lane(), k -> {
                char[] r = new char[width];
                java.util.Arrays.fill(r, ' ');
                return r;
            });
            row[Math.min(width - 1, col(m.time(), t0, range, width))] = m.category().equals("exception") ? '!' : '^';
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Timeline ").append(clock(t0, zone)).append(" -> ").append(clock(t.end(), zone)).append(" (")
                .append(duration(range)).append("), ").append(layout.lanes.size()).append(" thread(s), ")
                .append(t.spans().size()).append(" span(s)\n");
        String pad = " ".repeat(labelWidth + 1);
        sb.append(pad).append(axisLabels(range, width)).append('\n');
        sb.append(pad).append(axisLine(width)).append('\n');
        for (String lane : layout.lanes) {
            char[][] rows = grid.get(lane);
            for (int r = 0; r < rows.length; r++) {
                String name = r == 0 ? laneName(lane, multi) : "  ." + r;
                sb.append(fit(name, labelWidth)).append(' ').append(new String(rows[r]).stripTrailing()).append('\n');
            }
            char[] marks = markRows.get(lane);
            if (marks != null) {
                sb.append(fit("  marks", labelWidth)).append(' ').append(new String(marks).stripTrailing()).append('\n');
            }
        }
        for (String lane : markRows.keySet()) {
            if (!grid.containsKey(lane)) {
                sb.append(fit(laneName(lane, multi), labelWidth)).append(' ').append(new String(markRows.get(lane))
                        .stripTrailing()).append('\n');
            }
        }
        sb.append('\n');
        Analysis a = new Analysis(t, 0);
        Table legend = Table.of("", "block", "count", "total", "max");
        for (Analysis.LabelStats l : a.labels()) {
            Character c = letters.get(l.label());
            legend.row(c == null ? String.valueOf(symbolFor(l.category())) : String.valueOf(c), l.label(), l.count(),
                    duration(l.total()), duration(l.max()));
        }
        sb.append(legend.maxWidth(60).render(Table.Format.ASCII));
        List<String> symbols = new ArrayList<>();
        if (t.spans().stream().anyMatch(x -> x.category().equals("lock-wait"))) {
            symbols.add("~ lock wait");
        }
        if (t.spans().stream().anyMatch(x -> x.category().equals("lock-hold"))) {
            symbols.add("= lock held");
        }
        if (t.spans().stream().anyMatch(x -> x.category().equals("lock"))) {
            symbols.add("% lock (wait and hold)");
        }
        if (t.marks().stream().anyMatch(x -> x.category().equals("exception"))) {
            symbols.add("! exception");
        }
        if (t.marks().stream().anyMatch(x -> !x.category().equals("exception"))) {
            symbols.add("^ marker");
        }
        if (!symbols.isEmpty()) {
            sb.append(String.join("  ", symbols)).append('\n');
        }
        t.notes().forEach(n -> sb.append("note: ").append(n).append('\n'));
        if (layout.hiddenNote() != null) {
            sb.append("note: ").append(layout.hiddenNote()).append('\n');
        }
        return sb.toString();
    }

    private static Map<String, Character> letters(Timeline t) {
        Map<String, Long> totals = new HashMap<>();
        for (Span s : t.spans()) {
            if (!s.category().startsWith("lock")) {
                totals.merge(s.label(), s.duration(), Long::sum);
            }
        }
        List<String> order = totals.keySet().stream()
                .sorted(Comparator.comparing((String l) -> totals.get(l)).reversed().thenComparing(l -> l)).toList();
        Map<String, Character> m = new LinkedHashMap<>();
        for (int i = 0; i < order.size(); i++) {
            m.put(order.get(i), i < LETTERS.length() ? LETTERS.charAt(i) : '#');
        }
        return m;
    }

    private static char symbol(Span s, Map<String, Character> letters) {
        return s.category().startsWith("lock") ? symbolFor(s.category()) : letters.getOrDefault(s.label(), '#');
    }

    private static char symbolFor(String category) {
        return switch (category) {
            case "lock-wait" -> '~';
            case "lock-hold" -> '=';
            case "lock" -> '%';
            default -> '#';
        };
    }

    private static int col(long time, long t0, long range, int width) {
        return (int) Math.min(width, Math.max(0, Math.round((double) (time - t0) * width / range)));
    }

    private static String axisLabels(long range, int width) {
        char[] line = new char[width + 12];
        java.util.Arrays.fill(line, ' ');
        int ticks = Math.max(2, Math.min(5, width / 20));
        for (int i = 0; i <= ticks; i++) {
            int pos = i * (width - 1) / ticks;
            String label = i == 0 ? "0" : "+" + duration(range * i / ticks);
            int at = i == ticks ? Math.max(0, pos - label.length() + 1) : pos;
            for (int k = 0; k < label.length() && at + k < line.length; k++) {
                line[at + k] = label.charAt(k);
            }
        }
        return new String(line).stripTrailing();
    }

    private static String axisLine(int width) {
        char[] line = new char[width];
        java.util.Arrays.fill(line, '-');
        int ticks = Math.max(2, Math.min(5, width / 20));
        for (int i = 0; i <= ticks; i++) {
            line[i * (width - 1) / ticks] = '|';
        }
        return new String(line);
    }

    private static String laneName(String lane, boolean multi) {
        return multi ? lane : lane.substring(lane.indexOf(" / ") + 3);
    }

    private static String fit(String s, int width) {
        return s.length() > width ? s.substring(0, width - 1) + "…" : s + " ".repeat(width - s.length());
    }

    // ---- Mermaid

    static String mermaid(Timeline t) {
        StringBuilder sb = new StringBuilder("gantt\n    title Timeline\n    dateFormat x\n    axisFormat %H:%M:%S.%L\n");
        boolean multi = t.multiProcess();
        Map<String, List<Span>> byLane = new LinkedHashMap<>();
        t.lanes().forEach(l -> byLane.put(l, new ArrayList<>()));
        t.spans().forEach(s -> byLane.get(s.lane()).add(s));
        Map<String, List<Mark>> marks = new LinkedHashMap<>();
        t.marks().forEach(m -> marks.computeIfAbsent(m.lane(), k -> new ArrayList<>()).add(m));
        int n = 0;
        for (Map.Entry<String, List<Span>> e : byLane.entrySet()) {
            sb.append("    section ").append(mermaidText(laneName(e.getKey(), multi))).append('\n');
            for (Span s : e.getValue()) {
                long a = Math.floorDiv(s.start(), 1000);
                long b = Math.max(a + 1, Math.floorDiv(s.end(), 1000));
                String tag = s.category().equals("lock-wait") || s.args().containsKey("threw") ? "crit, " : "";
                sb.append("    ").append(mermaidText(s.label())).append(" :").append(tag).append("t").append(n++).append(", ")
                        .append(a).append(", ").append(b).append('\n');
            }
            for (Mark m : marks.getOrDefault(e.getKey(), List.of())) {
                long a = Math.floorDiv(m.time(), 1000);
                sb.append("    ").append(mermaidText(m.label())).append(" :milestone, t").append(n++).append(", ").append(a)
                        .append(", ").append(a + 1).append('\n');
            }
        }
        return sb.toString();
    }

    private static String mermaidText(String s) {
        String r = s.replaceAll("[:;#\\n\\r]", " ").strip();
        return r.isEmpty() ? "-" : r;
    }

    // ---- PlantUML

    static String plantuml(Timeline t, int maxRows) {
        long t0 = t.start();
        long range = t.end() - t0;
        // PlantUML wants integer times: milliseconds, or microseconds for short timelines
        long unit = range >= 100_000 ? 1000 : 1;
        Layout layout = new Layout(t, maxRows);
        boolean multi = t.multiProcess();
        StringBuilder sb = new StringBuilder("@startuml\n");
        Map<String, String> ids = new LinkedHashMap<>();
        for (String lane : layout.lanes) {
            for (int r = 0; r < layout.rows(lane); r++) {
                String id = "L" + ids.size();
                ids.put(lane + "#" + r, id);
                String name = laneName(lane, multi) + (r == 0 ? "" : " ." + r);
                sb.append("concise \"").append(name.replace('"', '\'')).append("\" as ").append(id).append('\n');
            }
        }
        TreeMap<Long, Map<String, String>> changes = new TreeMap<>();
        for (Span s : t.spans()) {
            if (layout.row(s) < 0) {
                continue;
            }
            String id = ids.get(s.lane() + "#" + layout.row(s));
            long a = (s.start() - t0) / unit;
            long b = Math.max(a + 1, (s.end() - t0) / unit);
            changes.computeIfAbsent(b, k -> new LinkedHashMap<>()).putIfAbsent(id, "{-}");
            changes.computeIfAbsent(a, k -> new LinkedHashMap<>()).put(id, "\"" + s.label().replace('"', '\'') + "\"");
        }
        // every participant needs a state at 0: idle unless a span starts there
        Map<String, String> initial = changes.computeIfAbsent(0L, k -> new LinkedHashMap<>());
        ids.values().forEach(id -> initial.putIfAbsent(id, "{-}"));
        sb.append('\n');
        changes.forEach((time, states) -> {
            sb.append("@").append(time).append('\n');
            states.forEach((id, state) -> sb.append(id).append(" is ").append(state).append('\n'));
        });
        int marks = 0;
        for (Mark m : t.marks()) {
            if (marks++ >= 20) {
                break;
            }
            long at = (m.time() - t0) / unit;
            sb.append("highlight ").append(at).append(" to ").append(at + Math.max(1, range / unit / 200))
                    .append(" #Gold : ").append(m.label().replace('\n', ' ')).append('\n');
        }
        if (layout.hiddenNote() != null) {
            sb.append("' ").append(layout.hiddenNote()).append('\n');
        }
        sb.append("@enduml\n");
        return sb.toString();
    }

    // ---- Chrome trace event format

    static String chrome(Timeline t, ZoneId zone) {
        long t0 = t.start();
        Layout layout = new Layout(t, Integer.MAX_VALUE);
        StringWriter out = new StringWriter();
        JsonWriter w = new JsonWriter(out);
        Map<String, Integer> pids = new LinkedHashMap<>();
        Map<String, Integer> tids = new LinkedHashMap<>();
        Map<String, Boolean> nested = new HashMap<>();
        for (String lane : layout.lanes) {
            nested.put(lane, properlyNested(t, lane));
        }
        w.beginObject();
        w.field("displayTimeUnit", "ms");
        w.name("otherData").beginObject().field("start", clock(t0, zone)).field("generator", "mcp-set-tools timeline").endObject();
        w.name("traceEvents").beginArray();
        for (String lane : layout.lanes) {
            String process = lane.substring(0, lane.indexOf(" / "));
            String thread = lane.substring(lane.indexOf(" / ") + 3);
            int pid = pids.computeIfAbsent(process, p -> pids.size() + 1);
            if (tids.keySet().stream().noneMatch(k -> k.startsWith(process + " / "))) {
                meta(w, "process_name", pid, 0, process);
            }
            int rows = nested.get(lane) ? 1 : layout.rows(lane);
            for (int r = 0; r < rows; r++) {
                int tid = tids.size() + 1;
                tids.put(lane + "#" + r, tid);
                meta(w, "thread_name", pid, tid, thread + (r == 0 ? "" : " ." + r));
                w.beginObject().field("name", "thread_sort_index").field("ph", "M").field("pid", pid).field("tid", tid)
                        .name("args").beginObject().field("sort_index", tid).endObject().endObject();
            }
        }
        for (Span s : t.spans()) {
            String process = s.process();
            int row = nested.get(s.lane()) ? 0 : layout.row(s);
            w.beginObject().field("name", s.label()).field("cat", s.category()).field("ph", "X").field("ts", s.start() - t0)
                    .field("dur", Math.max(1, s.duration())).field("pid", pids.get(process))
                    .field("tid", tids.get(s.lane() + "#" + row));
            if (!s.args().isEmpty()) {
                w.name("args").beginObject();
                s.args().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> w.field(e.getKey(), e.getValue()));
                w.endObject();
            }
            w.endObject();
        }
        for (Mark m : t.marks()) {
            Integer tid = tids.get(m.lane() + "#0");
            int pid = pids.computeIfAbsent(m.process(), p -> pids.size() + 1);
            w.beginObject().field("name", m.label()).field("cat", m.category()).field("ph", "i").field("s", "t")
                    .field("ts", m.time() - t0).field("pid", pid).field("tid", tid == null ? 0 : tid).endObject();
        }
        w.endArray();
        w.endObject();
        w.flush();
        return out.toString();
    }

    private static void meta(JsonWriter w, String name, int pid, int tid, String value) {
        w.beginObject().field("name", name).field("ph", "M").field("pid", pid).field("tid", tid).name("args").beginObject()
                .field("name", value).endObject().endObject();
    }

    /** True when any two spans of the lane are disjoint or one contains the other (a call stack). */
    private static boolean properlyNested(Timeline t, String lane) {
        List<Span> spans = t.spans().stream().filter(s -> s.lane().equals(lane))
                .sorted(Comparator.comparingLong(Span::start).thenComparing(Comparator.comparingLong(Span::duration).reversed()))
                .toList();
        java.util.Deque<Span> stack = new java.util.ArrayDeque<>();
        for (Span s : spans) {
            while (!stack.isEmpty() && stack.peek().end() <= s.start()) {
                stack.pop();
            }
            if (!stack.isEmpty() && s.end() > stack.peek().end()) {
                return false;
            }
            stack.push(s);
        }
        return true;
    }

    // ---- HTML

    static String html(Timeline t, int maxRows, ZoneId zone) {
        long t0 = t.start();
        long range = Math.max(1, t.end() - t0);
        Layout layout = new Layout(t, maxRows);
        boolean multi = t.multiProcess();
        int width = 1200;
        int rowH = 18;
        int labelW = 240;
        int axisH = 28;
        Map<String, Integer> top = new LinkedHashMap<>();
        int y = axisH;
        for (String lane : layout.lanes) {
            top.put(lane, y);
            y += layout.rows(lane) * rowH + 6;
        }
        int height = y + 10;
        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(labelW + width + 20).append("\" height=\"")
                .append(height).append("\" font-family=\"system-ui, sans-serif\" font-size=\"11\">\n");
        for (int i = 0; i <= 10; i++) {
            int x = labelW + i * width / 10;
            svg.append("<line x1=\"").append(x).append("\" y1=\"").append(axisH - 6).append("\" x2=\"").append(x)
                    .append("\" y2=\"").append(height - 10).append("\" stroke=\"#ddd\"/>\n");
            svg.append("<text x=\"").append(x + 2).append("\" y=\"").append(axisH - 10).append("\" fill=\"#555\">")
                    .append(i == 0 ? "0" : "+" + esc(duration(range * i / 10))).append("</text>\n");
        }
        for (String lane : layout.lanes) {
            int ly = top.get(lane);
            svg.append("<text x=\"4\" y=\"").append(ly + 13).append("\" fill=\"#222\">").append(esc(fitHtml(laneName(lane, multi))))
                    .append("<title>").append(esc(lane)).append("</title></text>\n");
            svg.append("<line x1=\"0\" y1=\"").append(ly + layout.rows(lane) * rowH + 3).append("\" x2=\"")
                    .append(labelW + width).append("\" y2=\"").append(ly + layout.rows(lane) * rowH + 3)
                    .append("\" stroke=\"#eee\"/>\n");
        }
        for (Span s : t.spans()) {
            if (layout.row(s) < 0) {
                continue;
            }
            double x = labelW + (double) (s.start() - t0) * width / range;
            double w = Math.max(1, (double) s.duration() * width / range);
            int sy = top.get(s.lane()) + layout.row(s) * rowH;
            StringBuilder tip = new StringBuilder(s.label()).append("\n+").append(duration(s.start() - t0)).append(", ")
                    .append(duration(s.duration())).append("\n").append(s.lane());
            s.args().forEach((k, v) -> tip.append('\n').append(k).append(": ").append(v));
            svg.append(String.format(Locale.ROOT, "<rect x=\"%.1f\" y=\"%d\" width=\"%.1f\" height=\"%d\" rx=\"2\" fill=\"%s\">",
                    x, sy + 1, w, rowH - 3, color(s))).append("<title>").append(esc(tip.toString())).append("</title></rect>\n");
            if (w > 40) {
                svg.append(String.format(Locale.ROOT, "<text x=\"%.1f\" y=\"%d\" fill=\"#000\" clip-path=\"none\">", x + 3, sy + 13))
                        .append(esc(clip(s.label(), (int) (w / 6.5)))).append("</text>\n");
            }
        }
        for (Mark m : t.marks()) {
            Integer ly = top.get(m.lane());
            if (ly == null) {
                continue;
            }
            double x = labelW + (double) (m.time() - t0) * width / range;
            svg.append(String.format(Locale.ROOT, "<path d=\"M%.1f %d l4 7 h-8 z\" fill=\"%s\">", x, ly, m.category()
                    .equals("exception") ? "#d00" : "#c80")).append("<title>").append(esc(m.label() + "\n+"
                            + duration(m.time() - t0))).append("</title></path>\n");
        }
        svg.append("</svg>\n");
        Analysis a = new Analysis(t, 20);
        Table lanes = Table.of("thread", "spans", "busy", "busy %");
        a.lanes().forEach(l -> lanes.row(l.lane(), l.spans(), duration(l.busy()), Math.round(100.0 * l.busy() / range) + "%"));
        Table labels = Table.of("block", "count", "total", "max");
        a.labels().stream().limit(30).forEach(l -> labels.row(l.label(), l.count(), duration(l.total()), duration(l.max())));
        StringBuilder html = new StringBuilder("<!doctype html>\n<html><head><meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<title>Timeline</title>\n<style>\n"
                + "body{font-family:system-ui,sans-serif;margin:16px;color:#222;background:#fff}\n"
                + ".scroll{overflow-x:auto;border:1px solid #ddd}\ntable{border-collapse:collapse;margin:12px 0}\n"
                + "td,th{border:1px solid #ddd;padding:3px 8px;font-size:13px;text-align:left}\nth{background:#f5f5f5}\n"
                + "</style></head><body>\n");
        html.append("<h1>Timeline</h1>\n<p>").append(esc(clock(t0, zone))).append(" &rarr; ").append(esc(clock(t.end(), zone)))
                .append(" (").append(esc(duration(range))).append("), ").append(layout.lanes.size()).append(" thread(s), ")
                .append(t.spans().size()).append(" span(s); at most ").append(a.maxConcurrency())
                .append(" thread(s) busy at once. Hover a block for details.</p>\n");
        html.append("<div class=\"scroll\">\n").append(svg).append("</div>\n");
        html.append("<h2>Threads</h2>\n").append(lanes.render(Table.Format.HTML));
        html.append("<h2>Blocks</h2>\n").append(labels.render(Table.Format.HTML));
        if (!a.overlaps().isEmpty()) {
            Table o = Table.of("block", "thread", "thread", "overlap", "at");
            a.overlaps().forEach(x -> o.row(x.label(), x.laneA(), x.laneB(), duration(x.duration()), "+" + duration(x.start() - t0)));
            html.append("<h2>Same block on several threads at once</h2>\n").append(o.render(Table.Format.HTML));
        }
        if (!a.locks().isEmpty()) {
            Table l = Table.of("lock", "waits", "total wait", "max wait", "holds", "total held");
            a.locks().forEach(x -> l.row(x.lock(), x.waits(), duration(x.totalWait()), duration(x.maxWait()), x.holds(),
                    duration(x.totalHold())));
            html.append("<h2>Locks</h2>\n").append(l.render(Table.Format.HTML));
        }
        t.notes().forEach(n -> html.append("<p><em>").append(esc(n)).append("</em></p>\n"));
        if (layout.hiddenNote() != null) {
            html.append("<p><em>").append(esc(layout.hiddenNote())).append("</em></p>\n");
        }
        return html.append("</body></html>\n").toString();
    }

    private static String color(Span s) {
        switch (s.category()) {
            case "lock-wait":
                return "#e57373";
            case "lock-hold":
                return "#ffb74d";
            case "lock":
                return "#ff8a65";
            case "failure":
                return "#ef9a9a";
            case "skipped":
                return "#e0e0e0";
            default:
                int h = Math.floorMod(s.label().hashCode(), 360);
                return "hsl(" + h + ",55%," + (72 - Math.min(s.depth(), 4) * 4) + "%)";
        }
    }

    private static String fitHtml(String s) {
        return s.length() > 36 ? s.substring(0, 35) + "…" : s;
    }

    private static String clip(String s, int chars) {
        return s.length() <= chars ? s : s.substring(0, Math.max(1, chars - 1)) + "…";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ---- formatting

    /** {@code 850µs}, {@code 12.5ms}, {@code 1.23s}, {@code 2m05s}. */
    public static String duration(long micros) {
        if (micros < 1000) {
            return micros + "µs";
        }
        if (micros < 1_000_000) {
            return trim(micros / 1000.0) + "ms";
        }
        if (micros < 60_000_000) {
            return trim(micros / 1_000_000.0) + "s";
        }
        long s = micros / 1_000_000;
        return s / 60 + "m" + String.format(Locale.ROOT, "%02d", s % 60) + "s";
    }

    private static String trim(double v) {
        String s = String.format(Locale.ROOT, v >= 100 ? "%.0f" : v >= 10 ? "%.1f" : "%.2f", v);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    /** Time of day for 1970-based (time-only) timestamps, else the date and time in {@code zone}. */
    public static String clock(long micros, ZoneId zone) {
        Instant i = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000), Math.floorMod(micros, 1_000_000) * 1000L);
        ZonedDateTime z = i.atZone(zone);
        if (z.toLocalDate().equals(LocalDate.of(1970, 1, 1))) {
            return z.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
        }
        return z.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
    }
}
