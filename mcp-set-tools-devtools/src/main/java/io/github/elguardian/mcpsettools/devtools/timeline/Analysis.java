package io.github.elguardian.mcpsettools.devtools.timeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Numbers about a timeline: per thread, concurrency, overlapping blocks, locks. */
public final class Analysis {

    /** @param busy time covered by at least one span (nested spans counted once) */
    public record Lane(String lane, int spans, long busy, long first, long last) {
    }

    /** The same label running on two threads at once. */
    public record Overlap(String label, String laneA, String laneB, long start, long end) {
        public long duration() {
            return end - start;
        }
    }

    public record LabelStats(String label, String category, int count, long total, long max) {
    }

    public record LockStats(String lock, int waits, long totalWait, long maxWait, int holds, long totalHold) {
    }

    private final long start;
    private final long end;
    private final List<Lane> lanes = new ArrayList<>();
    private int maxConcurrency;
    private long maxConcurrencyAt;
    private final List<Overlap> overlaps = new ArrayList<>();
    private int overlapCount;
    private final List<LabelStats> labels = new ArrayList<>();
    private final List<LockStats> locks = new ArrayList<>();

    public Analysis(Timeline t, int maxOverlaps) {
        start = t.start();
        end = t.end();
        Map<String, List<Span>> byLane = new LinkedHashMap<>();
        t.lanes().forEach(l -> byLane.put(l, new ArrayList<>()));
        t.spans().forEach(s -> byLane.get(s.lane()).add(s));
        List<long[]> busyIntervals = new ArrayList<>();
        byLane.forEach((lane, spans) -> {
            List<long[]> merged = union(spans);
            long busy = merged.stream().mapToLong(i -> i[1] - i[0]).sum();
            busyIntervals.addAll(merged);
            long first = spans.stream().mapToLong(Span::start).min().orElse(0);
            long last = spans.stream().mapToLong(Span::end).max().orElse(0);
            if (!spans.isEmpty()) {
                lanes.add(new Lane(lane, spans.size(), busy, first, last));
            }
        });
        concurrency(busyIntervals);
        Map<String, List<Span>> byLabel = new LinkedHashMap<>();
        t.spans().forEach(s -> byLabel.computeIfAbsent(s.label(), k -> new ArrayList<>()).add(s));
        byLabel.forEach((label, spans) -> {
            labels.add(new LabelStats(label, spans.get(0).category(), spans.size(),
                    spans.stream().mapToLong(Span::duration).sum(), spans.stream().mapToLong(Span::duration).max().orElse(0)));
            overlaps(label, spans, maxOverlaps);
        });
        labels.sort(Comparator.comparingLong(LabelStats::total).reversed());
        overlaps.sort(Comparator.comparingLong(Overlap::duration).reversed());
        if (overlaps.size() > maxOverlaps) {
            overlaps.subList(maxOverlaps, overlaps.size()).clear();
        }
        lockStats(t);
    }

    private static List<long[]> union(List<Span> spans) {
        List<long[]> sorted = spans.stream().map(s -> new long[] { s.start(), s.end() })
                .sorted(Comparator.comparingLong(a -> a[0])).toList();
        List<long[]> out = new ArrayList<>();
        for (long[] i : sorted) {
            if (!out.isEmpty() && i[0] <= out.get(out.size() - 1)[1]) {
                out.get(out.size() - 1)[1] = Math.max(out.get(out.size() - 1)[1], i[1]);
            } else {
                out.add(new long[] { i[0], i[1] });
            }
        }
        return out;
    }

    /** Most threads busy at once: a sweep over the per-thread busy intervals. */
    private void concurrency(List<long[]> intervals) {
        TreeMap<Long, Integer> delta = new TreeMap<>();
        for (long[] i : intervals) {
            if (i[1] > i[0]) {
                delta.merge(i[0], 1, Integer::sum);
                delta.merge(i[1], -1, Integer::sum);
            }
        }
        int now = 0;
        for (Map.Entry<Long, Integer> e : delta.entrySet()) {
            now += e.getValue();
            if (now > maxConcurrency) {
                maxConcurrency = now;
                maxConcurrencyAt = e.getKey();
            }
        }
    }

    private void overlaps(String label, List<Span> spans, int max) {
        List<Span> sorted = spans.stream().sorted(Comparator.comparingLong(Span::start)).toList();
        List<Span> active = new ArrayList<>();
        for (Span s : sorted) {
            active.removeIf(a -> a.end() <= s.start());
            for (Span a : active) {
                if (!a.lane().equals(s.lane()) && a.overlaps(s)) {
                    overlapCount++;
                    if (overlaps.size() < max * 4) {
                        overlaps.add(new Overlap(label, a.lane(), s.lane(), s.start(), Math.min(a.end(), s.end())));
                    }
                }
            }
            active.add(s);
        }
    }

    private void lockStats(Timeline t) {
        Map<String, long[]> acc = new LinkedHashMap<>();
        for (Span s : t.spans()) {
            if (!s.category().startsWith("lock")) {
                continue;
            }
            String lock = s.label().substring(s.label().indexOf(' ') + 1);
            long[] a = acc.computeIfAbsent(lock, k -> new long[5]);
            if (s.category().equals("lock-wait")) {
                a[0]++;
                a[1] += s.duration();
                a[2] = Math.max(a[2], s.duration());
            } else {
                a[3]++;
                a[4] += s.duration();
            }
        }
        acc.forEach((lock, a) -> locks.add(new LockStats(lock, (int) a[0], a[1], a[2], (int) a[3], a[4])));
        locks.sort(Comparator.comparingLong(LockStats::totalWait).reversed());
    }

    public long start() {
        return start;
    }

    public long end() {
        return end;
    }

    public List<Lane> lanes() {
        return lanes;
    }

    public int maxConcurrency() {
        return maxConcurrency;
    }

    public long maxConcurrencyAt() {
        return maxConcurrencyAt;
    }

    /** Longest overlaps of the same label on different threads. */
    public List<Overlap> overlaps() {
        return overlaps;
    }

    /** All overlapping pairs found, including those not listed. */
    public int overlapCount() {
        return overlapCount;
    }

    /** Labels by total time. */
    public List<LabelStats> labels() {
        return labels;
    }

    public List<LockStats> locks() {
        return locks;
    }
}
