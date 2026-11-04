package io.github.elguardian.mcpsettools.devtools.heap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code jcmd GC.class_histogram} / {@code jmap -histo} snapshots, and how classes grow between them. */
public final class HeapHistogram {

    public record Entry(String className, String module, long instances, long bytes) {
    }

    public record Snapshot(String source, int index, List<Entry> entries) {
        public long bytes() {
            return entries.stream().mapToLong(Entry::bytes).sum();
        }

        public long instances() {
            return entries.stream().mapToLong(Entry::instances).sum();
        }
    }

    /**
     * Change of one class from the first to the last snapshot.
     *
     * @param growingEverywhere it grew between every pair of consecutive snapshots (a leak candidate)
     */
    public record Growth(String className, long firstInstances, long lastInstances, long firstBytes, long lastBytes,
            boolean growingEverywhere) {
        public long bytesDelta() {
            return lastBytes - firstBytes;
        }

        public long instancesDelta() {
            return lastInstances - firstInstances;
        }
    }

    private static final Pattern HEADER = Pattern.compile("^\\s*num\\s+#instances\\s+#bytes\\s+class name.*$");
    private static final Pattern ROW = Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)(?:\\s+\\((.+)\\))?\\s*$");

    private HeapHistogram() {
    }

    /** Every histogram in the text (a header line starts each). */
    public static List<Snapshot> parse(String source, String text, int firstIndex) {
        List<Snapshot> snapshots = new ArrayList<>();
        List<Entry> current = null;
        for (String line : text.split("\r?\n")) {
            if (HEADER.matcher(line).matches()) {
                if (current != null) {
                    snapshots.add(new Snapshot(source, firstIndex + snapshots.size(), current));
                }
                current = new ArrayList<>();
                continue;
            }
            if (current == null) {
                continue;
            }
            Matcher m = ROW.matcher(line);
            if (m.matches()) {
                current.add(new Entry(m.group(3), m.group(4), Long.parseLong(m.group(1)), Long.parseLong(m.group(2))));
            }
        }
        if (current != null) {
            snapshots.add(new Snapshot(source, firstIndex + snapshots.size(), current));
        }
        return snapshots;
    }

    /** Growth per class across snapshots in order, most bytes gained first. */
    public static List<Growth> diff(List<Snapshot> snapshots) {
        if (snapshots.size() < 2) {
            return List.of();
        }
        List<Map<String, Entry>> maps = snapshots.stream().map(s -> {
            Map<String, Entry> m = new LinkedHashMap<>();
            s.entries().forEach(e -> m.merge(e.className(), e,
                    (a, b) -> new Entry(a.className(), a.module(), a.instances() + b.instances(), a.bytes() + b.bytes())));
            return m;
        }).toList();
        Map<String, Entry> last = maps.get(maps.size() - 1);
        List<Growth> result = new ArrayList<>();
        for (Entry e : last.values()) {
            Entry first = maps.get(0).get(e.className());
            boolean growing = true;
            for (int i = 1; i < maps.size(); i++) {
                Entry a = maps.get(i - 1).get(e.className());
                Entry b = maps.get(i).get(e.className());
                if (b == null || a != null && b.bytes() <= a.bytes()) {
                    growing = false;
                    break;
                }
            }
            result.add(new Growth(e.className(), first == null ? 0 : first.instances(), e.instances(),
                    first == null ? 0 : first.bytes(), e.bytes(), growing));
        }
        result.sort(Comparator.comparingLong(Growth::bytesDelta).reversed());
        return result;
    }
}
