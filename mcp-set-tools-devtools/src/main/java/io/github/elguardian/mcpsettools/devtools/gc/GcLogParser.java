package io.github.elguardian.mcpsettools.devtools.gc;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads JDK 9+ unified GC logs ({@code -Xlog:gc*}, any decorations, G1, Parallel, Serial, ZGC,
 * Shenandoah) and JDK 8 {@code -XX:+PrintGCDetails} logs (Parallel, CMS, G1).
 */
public final class GcLogParser {

    private static final Pattern DECORATION = Pattern.compile("^((?:\\[[^\\]]*\\])+)\\s?(.*)$");
    private static final Pattern BRACKET = Pattern.compile("\\[([^\\]]*)\\]");
    private static final Pattern WALL = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[.,]\\d{3}[+-]\\d{2}:?\\d{2}$");
    private static final Pattern UPTIME = Pattern.compile("^(\\d+(?:[.,]\\d+)?)s$");
    private static final Pattern GC_ID = Pattern.compile("^GC\\((\\d+)\\) (.*)$");
    /** {@code Pause Young (Normal) (G1 Evacuation Pause) 48M->1M(128M) 0,514ms} and friends. */
    private static final Pattern EVENT = Pattern.compile("^((?:Pause|Concurrent|Minor|Major|Garbage)[A-Za-z ]*?)"
            + "((?: \\([^()]*(?:\\([^()]*\\))?[^()]*\\))*)"
            + "(?: (\\d+(?:[.,]\\d+)?)([BKMG])(?:\\(\\d+%\\))?->(\\d+(?:[.,]\\d+)?)([BKMG])(?:\\(\\d+%\\))?(?:\\((\\d+(?:[.,]\\d+)?)([BKMG])\\))?)?"
            + " (\\d+(?:[.,]\\d+)?)(ms|s)$");
    private static final Pattern USING = Pattern.compile("^Using (.+)$");
    /** JDK 8: {@code 2024-...: 1.234: [GC (Allocation Failure) ... 33280K->6120K(125952K), 0.0061230 secs]}. */
    private static final Pattern JDK8 = Pattern.compile(
            "^(?:(\\d{4}-\\d{2}-\\d{2}T[\\d:.,]+[+-]\\d{4}): )?(?:(\\d+[.,]\\d+): )?\\[(Full GC|GC)(?: pause)?((?: \\([^()]*(?:\\([^()]*\\))?[^()]*\\))*)(.*)$");
    private static final Pattern JDK8_HEAP = Pattern.compile("(\\d+)K->(\\d+)K\\((\\d+)K\\)");
    private static final Pattern JDK8_G1_HEAP = Pattern.compile(
            "Heap: (\\d+(?:\\.\\d+)?)([BKMG])\\(\\d+(?:\\.\\d+)?[BKMG]\\)->(\\d+(?:\\.\\d+)?)([BKMG])\\((\\d+(?:\\.\\d+)?)([BKMG])\\)");
    private static final Pattern GENERATION = Pattern.compile("\\[[A-Za-z ]+: [^\\[\\]]*\\]");
    private static final Pattern JDK8_SECS = Pattern.compile(", (\\d+[.,]\\d+) secs\\]");
    private static final Pattern HUMONGOUS = Pattern.compile("Humongous regions: (\\d+)->(\\d+)");
    private static final DateTimeFormatter WALL_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private GcLogParser() {
    }

    public static GcLog parse(String source, String text) {
        List<GcEvent> events = new ArrayList<>();
        Map<String, Integer> warnings = new LinkedHashMap<>();
        String collector = null;
        boolean unified = false;
        boolean jdk8 = false;
        int humongousPeak = 0;
        String[] lines = text.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains("To-space exhausted") || line.contains("Evacuation Failure") || line.contains("to-space overflow")) {
                warnings.merge("To-space exhausted / evacuation failure (old generation full during a young GC)", 1, Integer::sum);
            }
            if (line.contains("Allocation Stall")) {
                warnings.merge("Allocation stalls (ZGC could not keep up with allocation)", 1, Integer::sum);
            }
            if (line.contains("concurrent mode failure")) {
                warnings.merge("CMS concurrent mode failure", 1, Integer::sum);
            }
            Matcher h = HUMONGOUS.matcher(line);
            if (h.find()) {
                humongousPeak = Math.max(humongousPeak, Math.max(Integer.parseInt(h.group(1)), Integer.parseInt(h.group(2))));
            }
            Matcher d = DECORATION.matcher(line);
            if (d.matches()) {
                long wall = Long.MIN_VALUE;
                double uptime = -1;
                Matcher b = BRACKET.matcher(d.group(1));
                while (b.find()) {
                    String v = b.group(1).strip();
                    if (WALL.matcher(v).matches()) {
                        wall = wall(v);
                    } else {
                        Matcher u = UPTIME.matcher(v);
                        if (u.matches()) {
                            uptime = num(u.group(1));
                        }
                    }
                }
                String msg = d.group(2).strip();
                Matcher using = USING.matcher(msg);
                if (using.matches() && collector == null) {
                    collector = using.group(1);
                    unified = true;
                    continue;
                }
                Matcher id = GC_ID.matcher(msg);
                if (!id.matches()) {
                    continue;
                }
                unified = true;
                Matcher e = EVENT.matcher(id.group(2));
                if (e.matches()) {
                    String kind = e.group(1).strip();
                    String detail = parens(e.group(2));
                    boolean pause = kind.startsWith("Pause");
                    double ms = num(e.group(9)) * (e.group(10).equals("s") ? 1000 : 1);
                    events.add(new GcEvent(Long.parseLong(id.group(1)), uptime, wall, kind, detail, pause,
                            kb(e.group(3), e.group(4)), kb(e.group(5), e.group(6)), kb(e.group(7), e.group(8)), ms));
                }
                continue;
            }
            Matcher j = JDK8.matcher(line.strip());
            if (j.matches()) {
                jdk8 = true;
                String rest = j.group(5);
                Matcher secs = JDK8_SECS.matcher(rest);
                StringBuilder block = new StringBuilder(rest);
                int k = i + 1;
                // G1 on JDK 8 prints the heap and the end of the pause on the following lines
                while (!secs.find() && k < lines.length && k < i + 40) {
                    block.append(' ').append(lines[k]);
                    secs = JDK8_SECS.matcher(block);
                    k++;
                }
                if (!secs.find(0)) {
                    continue;
                }
                long before = -1;
                long after = -1;
                long cap = -1;
                // generations and metaspace are bracketed ([PSYoungGen: a->b(c)]); the whole heap is the triple outside
                String whole = GENERATION.matcher(block.substring(0, secs.start())).replaceAll(" ");
                Matcher heap = JDK8_HEAP.matcher(whole);
                while (heap.find()) {
                    before = Long.parseLong(heap.group(1));
                    after = Long.parseLong(heap.group(2));
                    cap = Long.parseLong(heap.group(3));
                }
                StringBuilder g1 = new StringBuilder(block);
                for (int x = k; x < lines.length && x < k + 10 && before < 0; x++) {
                    g1.append(' ').append(lines[x]);
                }
                Matcher g1heap = JDK8_G1_HEAP.matcher(g1);
                if (before < 0 && g1heap.find()) {
                    before = kb(g1heap.group(1), g1heap.group(2));
                    after = kb(g1heap.group(3), g1heap.group(4));
                    cap = kb(g1heap.group(5), g1heap.group(6));
                }
                String kind = j.group(3).equals("Full GC") ? "Pause Full" : "Pause Young";
                String detail = parens(j.group(4));
                if (detail.contains("CMS Initial Mark") || detail.contains("CMS Final Remark")) {
                    kind = "Pause Remark";
                }
                events.add(new GcEvent(-1, j.group(2) == null ? -1 : num(j.group(2)), j.group(1) == null ? Long.MIN_VALUE
                        : wall(j.group(1)), kind, detail, true, before, after, cap, num(secs.group(1)) * 1000));
                if (collector == null && line.contains("PSYoungGen")) {
                    collector = "Parallel";
                } else if (collector == null && line.contains("ParNew")) {
                    collector = "CMS";
                } else if (collector == null && line.contains("GC pause")) {
                    collector = "G1";
                }
            }
        }
        List<String> w = new ArrayList<>();
        warnings.forEach((k, v) -> w.add(k + ": " + v));
        if (humongousPeak > 0) {
            w.add("Humongous regions in use, peak " + humongousPeak + " (objects of half a region or more)");
        }
        return new GcLog(source, unified ? "unified" : jdk8 ? "jdk8" : "unknown", collector, events, w);
    }

    private static String parens(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '(') {
                if (depth++ > 0) {
                    cur.append(c);
                }
            } else if (c == ')') {
                if (--depth > 0) {
                    cur.append(c);
                } else {
                    parts.add(cur.toString().strip());
                    cur.setLength(0);
                }
            } else if (depth > 0) {
                cur.append(c);
            }
        }
        return String.join(", ", parts);
    }

    private static long kb(String n, String unit) {
        if (n == null) {
            return -1;
        }
        double v = num(n);
        return Math.round(switch (unit) {
            case "B" -> v / 1024;
            case "K" -> v;
            case "M" -> v * 1024;
            default -> v * 1024 * 1024;
        });
    }

    /** {@code 0.514} or {@code 0,514}: the JVM formats with the default locale. */
    static double num(String s) {
        return Double.parseDouble(s.replace(',', '.'));
    }

    private static long wall(String s) {
        try {
            var i = OffsetDateTime.parse(s.replace(',', '.'), WALL_FORMAT).toInstant();
            return i.getEpochSecond() * 1_000_000 + i.getNano() / 1000;
        } catch (DateTimeParseException e) {
            return Long.MIN_VALUE;
        }
    }
}
