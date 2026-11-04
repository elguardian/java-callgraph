package gr.gousiosg.javacg.devtools.threads;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gr.gousiosg.javacg.commons.stack.StackTrace;
import gr.gousiosg.javacg.commons.stack.StackTraceParser;

/**
 * Parses thread dumps as {@code jstack}, {@code jcmd Thread.print [-l]} and {@code kill -3}
 * print them (JDK 8 to 25), several per text, also when they are embedded in a console or
 * server log.
 */
public final class ThreadDumpParser {

    private static final Pattern DUMP = Pattern.compile("^Full thread dump (.*?):?\\s*$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}.*$");
    private static final Pattern HEADER = Pattern.compile("^\"(.*)\"((?:\\s.*)?)$");
    private static final Pattern ID = Pattern.compile("(?:^|\\s)#(\\d+)");
    private static final Pattern CPU = Pattern.compile("\\scpu=([\\d.,]+)ms");
    private static final Pattern ELAPSED = Pattern.compile("\\selapsed=([\\d.,]+)s");
    private static final Pattern NID = Pattern.compile("\\snid=(\\S+)");
    private static final Pattern STATE = Pattern.compile("^\\s+java\\.lang\\.Thread\\.State: (\\w+)(?: \\((.*)\\))?\\s*$");
    private static final Pattern LOCK = Pattern.compile(
            "^\\s+- (locked|waiting to lock|waiting on|parking to wait for|eliminated)\\s+<(0x[0-9a-f]+|no object reference available)>"
                    + "(?:\\s+\\(a ([^)]+)\\))?.*$");
    private static final Pattern OWNABLE = Pattern.compile("^\\s+- <(0x[0-9a-f]+)> \\(a ([^)]+)\\)\\s*$");
    private static final Pattern DEADLOCK_THREAD = Pattern.compile("^\"(.*)\":\\s*$");

    private ThreadDumpParser() {
    }

    public static List<ThreadDump> parse(String source, String text) {
        List<ThreadDump> dumps = new ArrayList<>();
        String[] lines = text.split("\r?\n", -1);
        int i = 0;
        String previous = null;
        while (i < lines.length) {
            Matcher d = DUMP.matcher(lines[i]);
            if (d.matches()) {
                String ts = previous != null && DATE.matcher(previous.strip()).matches() ? previous.strip() : null;
                i = dump(source, dumps.size(), ts, d.group(1), lines, i + 1, dumps);
                previous = null;
                continue;
            }
            if (!lines[i].isBlank()) {
                previous = lines[i];
            }
            i++;
        }
        return dumps;
    }

    /** Reads one dump starting after its header; returns the index of the first line after it. */
    private static int dump(String source, int index, String ts, String jvm, String[] lines, int i, List<ThreadDump> out) {
        List<ThreadInfo> threads = new ArrayList<>();
        List<List<String>> deadlocks = new ArrayList<>();
        Builder t = null;
        boolean ownableSection = false;
        boolean inDeadlock = false;
        List<String> cycle = null;
        for (; i < lines.length; i++) {
            String line = lines[i];
            if (DUMP.matcher(line).matches() || i + 1 < lines.length && DUMP.matcher(lines[i + 1]).matches()
                    && DATE.matcher(line.strip()).matches()) {
                break;
            }
            if (line.startsWith("Found one Java-level deadlock")) {
                t = finish(t, threads);
                inDeadlock = true;
                cycle = new ArrayList<>();
                deadlocks.add(cycle);
                continue;
            }
            if (inDeadlock) {
                if (line.startsWith("Java stack information")) {
                    cycle = null;
                } else if (line.startsWith("Found ") && line.contains("deadlock")) {
                    inDeadlock = false;
                } else if (cycle != null) {
                    Matcher dt = DEADLOCK_THREAD.matcher(line);
                    if (dt.matches()) {
                        cycle.add(dt.group(1));
                    }
                }
                continue;
            }
            // the sections after the threads (jstack, kill -3 heap summary)
            if (line.startsWith("JNI global ref") || line.startsWith("Threads class SMR info") || line.equals("Heap")) {
                t = finish(t, threads);
                continue;
            }
            Matcher h = HEADER.matcher(line);
            if (h.matches() && !line.startsWith("\"\"")) {
                t = finish(t, threads);
                t = new Builder(h.group(1), h.group(2));
                ownableSection = false;
                continue;
            }
            if (t == null) {
                continue;
            }
            Matcher s = STATE.matcher(line);
            if (s.matches()) {
                t.state = s.group(1);
                t.detail = s.group(2);
                continue;
            }
            Optional<StackTrace.Frame> f = line.stripLeading().startsWith("at ") ? StackTraceParser.parseFrame(line)
                    : Optional.empty();
            if (f.isPresent()) {
                t.frames.add(f.get());
                continue;
            }
            Matcher l = LOCK.matcher(line);
            if (l.matches()) {
                ThreadInfo.Lock.Kind kind = switch (l.group(1)) {
                    case "locked" -> ThreadInfo.Lock.Kind.LOCKED;
                    case "waiting to lock" -> ThreadInfo.Lock.Kind.WAITING_TO_LOCK;
                    case "waiting on" -> ThreadInfo.Lock.Kind.WAITING_ON;
                    case "parking to wait for" -> ThreadInfo.Lock.Kind.PARKING;
                    default -> ThreadInfo.Lock.Kind.ELIMINATED;
                };
                String addr = l.group(2).startsWith("0x") ? l.group(2) : null;
                t.locks.add(new ThreadInfo.Lock(kind, addr, l.group(3), Math.max(0, t.frames.size() - 1)));
                continue;
            }
            if (line.strip().equals("Locked ownable synchronizers:")) {
                ownableSection = true;
                continue;
            }
            Matcher o = OWNABLE.matcher(line);
            if (ownableSection && o.matches()) {
                t.ownable.add(new ThreadInfo.Lock(ThreadInfo.Lock.Kind.OWNABLE, o.group(1), o.group(2), -1));
            }
        }
        finish(t, threads);
        out.add(new ThreadDump(source, index, ts, jvm, threads, deadlocks.stream().filter(c -> !c.isEmpty()).toList()));
        return i;
    }

    private static Builder finish(Builder t, List<ThreadInfo> into) {
        if (t != null) {
            into.add(t.build());
        }
        return null;
    }

    private static final class Builder {
        final String name;
        final String attrs;
        String state;
        String detail;
        final List<StackTrace.Frame> frames = new ArrayList<>();
        final List<ThreadInfo.Lock> locks = new ArrayList<>();
        final List<ThreadInfo.Lock> ownable = new ArrayList<>();

        Builder(String name, String attrs) {
            this.name = name;
            this.attrs = attrs == null ? "" : attrs;
        }

        ThreadInfo build() {
            Matcher id = ID.matcher(attrs);
            Matcher nid = NID.matcher(attrs);
            return new ThreadInfo(name, id.find() ? Long.parseLong(id.group(1)) : -1, attrs.contains(" daemon"),
                    nid.find() ? nid.group(1) : null, number(CPU, attrs), number(ELAPSED, attrs), state, detail, frames,
                    locks, ownable);
        }

        /** {@code cpu=12.34ms}, or {@code 12,34ms} under a locale with a decimal comma. */
        private static double number(Pattern p, String attrs) {
            Matcher m = p.matcher(attrs);
            if (!m.find()) {
                return -1;
            }
            String v = m.group(1);
            int comma = v.lastIndexOf(',');
            int dot = v.lastIndexOf('.');
            if (comma > dot) {
                v = v.replace(".", "").replace(',', '.');
            } else {
                v = v.replace(",", "");
            }
            try {
                return Double.parseDouble(v);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }
}
