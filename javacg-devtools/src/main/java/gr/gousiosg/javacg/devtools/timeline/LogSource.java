package gr.gousiosg.javacg.devtools.timeline;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gr.gousiosg.javacg.devtools.logs.LogFormat;
import gr.gousiosg.javacg.devtools.logs.LogReader;
import gr.gousiosg.javacg.devtools.logs.LogRecord;

/**
 * Spans from log records. A rule opens a span on a record matching {@code start} and closes it
 * on the next record matching {@code end}: on the same thread, or, with a {@code key} group,
 * on any thread whose end match has the same key value (a request handed between threads).
 * Records matching a marker become marks. Each file is a process.
 */
public final class LogSource {

    /**
     * @param key       name of a regex group present in both patterns whose value pairs start and end; null:
     *                  pair by thread
     */
    public record Rule(String name, Pattern start, Pattern end, String key) {
    }

    public record Marker(String name, Pattern match) {
    }

    private LogSource() {
    }

    public static Timeline read(List<Path> files, LogFormat format, List<Rule> rules, List<Marker> markers)
            throws IOException {
        List<Span> spans = new ArrayList<>();
        List<Mark> marks = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        long[] untimed = { 0 };
        for (Path f : files) {
            String process = f.getFileName().toString();
            Map<String, Deque<Object[]>> open = new LinkedHashMap<>();
            Map<String, Integer> depth = new HashMap<>();
            long[] last = { Long.MIN_VALUE };
            LogReader.read(List.of(f), format, r -> {
                if (!r.hasTime()) {
                    untimed[0]++;
                    return;
                }
                last[0] = Math.max(last[0], r.micros());
                String thread = r.thread() == null ? "main" : r.thread();
                String text = r.message();
                for (Rule rule : rules) {
                    Matcher end = rule.end().matcher(text);
                    if (end.find()) {
                        String k = key(rule, end, thread);
                        Deque<Object[]> stack = open.get(k);
                        if (stack != null && !stack.isEmpty()) {
                            Object[] o = stack.pop();
                            LogRecord s = (LogRecord) o[0];
                            String sThread = s.thread() == null ? "main" : s.thread();
                            Map<String, String> args = new LinkedHashMap<>();
                            args.put("start", "line " + s.line() + ": " + cut(s.message()));
                            args.put("end", "line " + r.line() + ": " + cut(text));
                            if (!sThread.equals(thread)) {
                                args.put("ended_on", thread);
                            }
                            depth.merge(rule.key() == null ? sThread : k, -1, Integer::sum);
                            spans.add(new Span(process, sThread, s.micros(), r.micros(), (String) o[1], "span", (int) o[2],
                                    args));
                            continue;
                        }
                    }
                    Matcher start = rule.start().matcher(text);
                    if (start.find()) {
                        String k = key(rule, start, thread);
                        String d = rule.key() == null ? thread : k;
                        int level = depth.merge(d, 1, Integer::sum) - 1;
                        String label = label(rule, start);
                        open.computeIfAbsent(k, x -> new ArrayDeque<>()).push(new Object[] { r, label, level });
                    }
                }
                for (Marker m : markers) {
                    if (m.match().matcher(r.fullMessage()).find()) {
                        marks.add(new Mark(process, thread, r.micros(), m.name() + ": " + cut(text), "marker"));
                    }
                }
            });
            int unclosed = 0;
            for (Deque<Object[]> stack : open.values()) {
                for (Object[] o : stack) {
                    LogRecord s = (LogRecord) o[0];
                    spans.add(new Span(process, s.thread() == null ? "main" : s.thread(), s.micros(), last[0], (String) o[1],
                            "span", (int) o[2], Map.of("open", "no end record before the end of the log")));
                    unclosed++;
                }
            }
            if (unclosed > 0) {
                notes.add(unclosed + " span(s) in " + process + " have no end record; drawn up to the last record");
            }
        }
        if (untimed[0] > 0) {
            notes.add(untimed[0] + " records without a parsable timestamp were skipped");
        }
        return new Timeline(spans, marks, notes);
    }

    private static String key(Rule rule, Matcher m, String thread) {
        if (rule.key() == null) {
            return rule.name() + "\u0000" + thread;
        }
        String v;
        try {
            v = m.group(rule.key());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Span rule '" + rule.name() + "': no group '" + rule.key()
                    + "' in its start and end patterns");
        }
        return rule.name() + "\u0000" + v;
    }

    /** The rule name, plus the {@code label} group or the key value when the pattern has one. */
    private static String label(Rule rule, Matcher m) {
        for (String g : new String[] { "label", rule.key() }) {
            if (g == null) {
                continue;
            }
            try {
                String v = m.group(g);
                if (v != null) {
                    return rule.name() + " " + v;
                }
            } catch (IllegalArgumentException e) {
                // no such group
            }
        }
        return rule.name();
    }

    private static String cut(String s) {
        return s.length() <= 120 ? s : s.substring(0, 119) + "…";
    }
}
