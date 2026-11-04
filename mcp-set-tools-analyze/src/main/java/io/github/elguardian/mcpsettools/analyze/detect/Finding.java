package io.github.elguardian.mcpsettools.analyze.detect;

import java.util.Comparator;
import java.util.List;

import io.github.elguardian.mcpsettools.core.MethodId;

/**
 * One reported problem.
 *
 * @param rule       detector rule id, e.g. {@code resource.leak}
 * @param severity   how bad it is if real
 * @param confidence how likely it is real
 * @param message    one-sentence description
 * @param method     where it is (null for findings without a single location)
 * @param line       source line, or -1
 * @param related    further locations (cycle members, the null check, ...)
 * @param evidence   runtime observations backing the finding; empty for static-only findings
 */
public record Finding(String rule, Severity severity, Confidence confidence, String message, MethodId method,
        int line, List<Location> related, List<String> evidence) {

    public enum Severity {
        HIGH, MEDIUM, LOW, INFO
    }

    public enum Confidence {
        HIGH, MEDIUM, LOW
    }

    /** A secondary location. */
    public record Location(MethodId method, int line, String note) {
    }

    /** Runtime-confirmed first, then by severity, confidence, rule and location. */
    public static final Comparator<Finding> RANKING = Comparator
            .comparing((Finding f) -> f.evidence().isEmpty())
            .thenComparing(Finding::severity)
            .thenComparing(Finding::confidence)
            .thenComparing(Finding::rule)
            .thenComparing(f -> f.method() == null ? "" : f.method().toString())
            .thenComparingInt(Finding::line);

    public Finding {
        related = List.copyOf(related);
        evidence = List.copyOf(evidence);
    }

    public boolean confirmedAtRuntime() {
        return !evidence.isEmpty();
    }

    /** A copy with more runtime evidence and, if given, a raised confidence. */
    public Finding withEvidence(List<String> more, Confidence raisedTo) {
        List<String> all = new java.util.ArrayList<>(evidence);
        all.addAll(more);
        Confidence c = raisedTo != null && raisedTo.compareTo(confidence) < 0 ? raisedTo : confidence;
        return new Finding(rule, severity, c, message, method, line, related, all);
    }
}
