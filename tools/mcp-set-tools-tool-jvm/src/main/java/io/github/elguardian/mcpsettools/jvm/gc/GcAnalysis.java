package io.github.elguardian.mcpsettools.jvm.gc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pause statistics, GC overhead, allocation rate and heap-after-GC trend of a GC log. */
public final class GcAnalysis {

    public record PauseStats(String kind, int count, double totalMs, double p50, double p90, double p99, double maxMs) {
    }

    /** The worst window by share of time paused. */
    public record Window(double start, double end, double pausedMs, double percent) {
    }

    /**
     * Heap after full GCs (or after every GC when there were none) as a line: KB per minute.
     *
     * @param suspicious the trend grows and the last value is above 70% of the capacity
     */
    public record Trend(int points, double kbPerMinute, long first, long last, long capacity, boolean suspicious) {
    }

    private final GcLog log;
    private final List<GcEvent> events;

    public GcAnalysis(GcLog log, double from, double to) {
        this.log = log;
        this.events = log.events().stream().filter(e -> (from < 0 || e.time() >= from) && (to < 0 || e.time() <= to)).toList();
    }

    public GcLog log() {
        return log;
    }

    public List<GcEvent> events() {
        return events;
    }

    public List<GcEvent> pauses() {
        return events.stream().filter(GcEvent::pause).toList();
    }

    /** First and last event time, seconds. */
    public double start() {
        return events.stream().mapToDouble(GcEvent::time).filter(t -> t >= 0).min().orElse(0);
    }

    public double end() {
        return events.stream().mapToDouble(e -> e.time() + e.millis() / 1000).max().orElse(0);
    }

    /** Pause statistics by kind (Pause Young, Pause Full, ...), plus an "all pauses" row first. */
    public List<PauseStats> pauseStats() {
        Map<String, List<Double>> byKind = new LinkedHashMap<>();
        List<Double> all = new ArrayList<>();
        for (GcEvent e : pauses()) {
            byKind.computeIfAbsent(e.kind(), k -> new ArrayList<>()).add(e.millis());
            all.add(e.millis());
        }
        List<PauseStats> result = new ArrayList<>();
        if (!all.isEmpty()) {
            result.add(stats("all pauses", all));
        }
        byKind.forEach((k, v) -> result.add(stats(k, v)));
        return result;
    }

    /** Concurrent cycles (G1 mark cycles, ZGC collections) by kind. */
    public List<PauseStats> concurrentStats() {
        Map<String, List<Double>> byKind = new LinkedHashMap<>();
        events.stream().filter(e -> !e.pause()).forEach(e -> byKind.computeIfAbsent(e.kind(), k -> new ArrayList<>()).add(e.millis()));
        List<PauseStats> result = new ArrayList<>();
        byKind.forEach((k, v) -> result.add(stats(k, v)));
        return result;
    }

    private static PauseStats stats(String kind, List<Double> ms) {
        List<Double> s = ms.stream().sorted().toList();
        return new PauseStats(kind, s.size(), s.stream().mapToDouble(Double::doubleValue).sum(), pct(s, 50), pct(s, 90),
                pct(s, 99), s.get(s.size() - 1));
    }

    private static double pct(List<Double> sorted, int p) {
        int i = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }

    /** Share of the log's time spent in pauses, percent. */
    public double overhead() {
        double span = end() - start();
        return span <= 0 ? 0 : 100 * pauses().stream().mapToDouble(GcEvent::millis).sum() / 1000 / span;
    }

    /** The window (a tenth of the log, at least 1 s, at most 60 s) with the highest pause share. */
    public Window worstWindow() {
        double span = end() - start();
        if (span <= 0 || pauses().isEmpty()) {
            return null;
        }
        double size = Math.max(1, Math.min(60, span / 10));
        Window worst = null;
        List<GcEvent> p = pauses();
        for (int i = 0; i < p.size(); i++) {
            double ws = p.get(i).time();
            double paused = 0;
            for (int j = i; j < p.size() && p.get(j).time() < ws + size; j++) {
                paused += p.get(j).millis();
            }
            double pctTime = 100 * paused / 1000 / size;
            if (worst == null || pctTime > worst.percent()) {
                worst = new Window(ws, ws + size, paused, Math.min(100, pctTime));
            }
        }
        return worst;
    }

    /** Average allocation rate, KB/s: heap grown between the end of one GC and the start of the next. */
    public double allocationRate() {
        double kb = 0;
        double seconds = 0;
        GcEvent prev = null;
        for (GcEvent e : events) {
            if (e.before() < 0 || e.after() < 0 || e.time() < 0) {
                continue;
            }
            if (prev != null && e.before() >= prev.after() && e.time() > prev.time()) {
                kb += e.before() - prev.after();
                seconds += e.time() - prev.time();
            }
            prev = e;
        }
        return seconds <= 0 ? 0 : kb / seconds;
    }

    public Trend heapTrend() {
        List<GcEvent> points = events.stream().filter(e -> e.full() && e.after() >= 0 && e.time() >= 0).toList();
        if (points.size() < 2) {
            points = events.stream().filter(e -> e.after() >= 0 && e.time() >= 0).toList();
        }
        if (points.size() < 2) {
            return null;
        }
        double n = points.size();
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (GcEvent e : points) {
            double x = e.time() / 60;
            sx += x;
            sy += e.after();
            sxx += x * x;
            sxy += x * e.after();
        }
        double den = n * sxx - sx * sx;
        double slope = den == 0 ? 0 : (n * sxy - sx * sy) / den;
        GcEvent last = points.get(points.size() - 1);
        long cap = last.capacity();
        boolean suspicious = slope > 0 && cap > 0 && last.after() > 0.7 * cap;
        return new Trend(points.size(), slope, points.get(0).after(), last.after(), cap, suspicious);
    }

    public List<GcEvent> longest(int n) {
        return pauses().stream().sorted(Comparator.comparingDouble(GcEvent::millis).reversed()).limit(n).toList();
    }

    public List<GcEvent> fullGcs() {
        return events.stream().filter(GcEvent::full).toList();
    }
}
