package io.github.elguardian.mcpsettools.issues;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scores how likely a known issue is the cause of a trace: the same signature is a certain
 * match; otherwise the root and outer exceptions, the application frames they share (frames near
 * the root cause weigh more) and the message shape add up.
 */
public final class IssueMatcher {

    public record Candidate(KnownIssue issue, double score, List<String> reasons) {
    }

    private IssueMatcher() {
    }

    public static List<Candidate> match(KnownIssue.Fingerprint trace, List<KnownIssue> issues, double threshold, int limit) {
        List<Candidate> result = new ArrayList<>();
        for (KnownIssue i : issues) {
            Candidate c = score(trace, i);
            if (c.score() >= threshold) {
                result.add(c);
            }
        }
        result.sort(Comparator.comparingDouble(Candidate::score).reversed().thenComparing(c -> c.issue().key()));
        // one candidate per issue key: its best trace
        List<Candidate> unique = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Candidate c : result) {
            if (keys.add(c.issue().key()) && unique.size() < limit) {
                unique.add(c);
            }
        }
        return unique;
    }

    public static Candidate score(KnownIssue.Fingerprint t, KnownIssue i) {
        List<String> reasons = new ArrayList<>();
        if (t.signatureId() != null && t.signatureId().equals(i.signatureId())) {
            return new Candidate(i, 1.0, List.of("same signature"));
        }
        double s = 0;
        if (eq(t.rootException(), i.rootException())) {
            s += 0.35;
            reasons.add("same root exception " + simple(t.rootException()));
        }
        if (eq(t.exception(), i.exception()) && !eq(t.exception(), t.rootException())) {
            s += 0.15;
            reasons.add("same outer exception " + simple(t.exception()));
        } else if (eq(t.exception(), i.exception())) {
            s += 0.05;
        }
        double total = 0;
        double shared = 0;
        int common = 0;
        Set<String> theirs = new HashSet<>(i.frames());
        for (int k = 0; k < t.frames().size(); k++) {
            double w = 1.0 / (1 + k);
            total += w;
            if (theirs.contains(t.frames().get(k))) {
                shared += w;
                common++;
            }
        }
        if (common > 0) {
            s += 0.4 * shared / total;
            reasons.add(common + " of " + t.frames().size() + " application frames in common");
        }
        double msg = similarity(t.messageTemplate(), i.messageTemplate());
        if (msg >= 0.6) {
            s += 0.1 * msg;
            reasons.add(msg == 1.0 ? "same message" : "similar message");
        }
        return new Candidate(i, Math.round(s * 100) / 100.0, reasons);
    }

    private static double similarity(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return 0;
        }
        if (a.equals(b)) {
            return 1;
        }
        Set<String> x = new HashSet<>(Arrays.asList(a.toLowerCase().split("\\W+")));
        Set<String> y = new HashSet<>(Arrays.asList(b.toLowerCase().split("\\W+")));
        Set<String> inter = new HashSet<>(x);
        inter.retainAll(y);
        Set<String> union = new HashSet<>(x);
        union.addAll(y);
        return union.isEmpty() ? 0 : (double) inter.size() / union.size();
    }

    private static boolean eq(String a, String b) {
        return a != null && a.equals(b);
    }

    private static String simple(String cls) {
        return cls.substring(cls.lastIndexOf('.') + 1);
    }
}
