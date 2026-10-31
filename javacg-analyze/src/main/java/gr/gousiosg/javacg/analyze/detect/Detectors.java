package gr.gousiosg.javacg.analyze.detect;

import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import gr.gousiosg.javacg.analyze.Model;

/** The built-in detectors and the text report. */
public final class Detectors {

    private Detectors() {
    }

    /** All detectors, in report order. */
    public static List<Detector> all() {
        return List.of(new RecursionDetector(), new DeadCodeDetector(), new ExceptionDetector(),
                new ResourceLeakDetector(), new LockOrderDetector(), new NullDereferenceDetector());
    }

    /** Detectors by id; unknown ids are an error. Empty selection means all. */
    public static List<Detector> select(Collection<String> ids) {
        if (ids.isEmpty()) {
            return all();
        }
        Map<String, Detector> byId = new LinkedHashMap<>();
        all().forEach(d -> byId.put(d.id(), d));
        List<Detector> result = new ArrayList<>();
        for (String id : ids) {
            Detector d = byId.get(id);
            if (d == null) {
                throw new IllegalArgumentException("Unknown detector " + id + "; known: " + byId.keySet());
            }
            result.add(d);
        }
        return result;
    }

    /** Runs the detectors and returns the findings, ranked. */
    public static List<Finding> run(Model model, List<Detector> detectors) {
        List<Finding> all = new ArrayList<>();
        for (Detector d : detectors) {
            all.addAll(d.run(model));
        }
        all.sort(Finding.RANKING);
        return all;
    }

    /** All rule ids with their descriptions. */
    public static Map<String, String> rules(List<Detector> detectors) {
        Map<String, String> rules = new LinkedHashMap<>();
        detectors.forEach(d -> rules.putAll(d.rules()));
        return rules;
    }

    /** Human-readable report. */
    public static void writeText(List<Finding> findings, Writer out) {
        PrintWriter w = new PrintWriter(out);
        for (Finding f : findings) {
            w.printf("[%s/%s] %s%s%n", f.severity(), f.confidence(), f.rule(),
                    f.confirmedAtRuntime() ? "  (confirmed at runtime)" : "");
            w.println("  " + f.message());
            if (f.method() != null) {
                w.println("  at " + f.method().javaName() + (f.line() > 0 ? " line " + f.line() : ""));
            }
            for (Finding.Location l : f.related()) {
                w.println("  " + l.note() + ": " + (l.method() == null ? "" : l.method().javaName())
                        + (l.line() > 0 ? " line " + l.line() : ""));
            }
            for (String e : f.evidence()) {
                w.println("  runtime: " + e);
            }
            w.println();
        }
        Map<String, Long> bySeverity = findings.stream()
                .collect(Collectors.groupingBy(f -> f.severity().name(), LinkedHashMap::new, Collectors.counting()));
        long confirmed = findings.stream().filter(Finding::confirmedAtRuntime).count();
        w.printf("%d finding(s) %s, %d confirmed at runtime%n", findings.size(), bySeverity, confirmed);
        w.flush();
    }

    /** Findings at or above a severity. */
    public static List<Finding> atLeast(List<Finding> findings, Finding.Severity min) {
        Set<Finding.Severity> keep = java.util.EnumSet.range(Finding.Severity.HIGH, min);
        return findings.stream().filter(f -> keep.contains(f.severity())).toList();
    }
}
