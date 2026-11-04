package io.github.elguardian.mcpsettools.status.tool;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Scores items by what they need from me and writes the markdown digest. */
final class StatusDigest {

    static final int NEEDS_YOU = 25;

    private StatusDigest() {
    }

    static void score(WorkItem w, LocalDate today) {
        w.reasons.removeIf(r -> !r.startsWith("details unavailable"));
        w.score = 0;
        if (w.isPullRequest()) {
            boolean mine = w.roles.contains(WorkItem.Role.AUTHOR);
            if (w.reviewPending) {
                add(w, 50, "your review is requested" + (w.lastPush != null ? " (last push " + age(w.lastPush) + " ago)" : ""));
            }
            if (mine && "changes_requested".equals(w.review)) {
                add(w, 45, "changes requested");
            }
            if (!w.activity.isEmpty()) {
                Set<String> who = new LinkedHashSet<>();
                w.activity.forEach(a -> who.add(a.author()));
                add(w, 40, w.activity.size() + " new " + (w.activity.size() == 1 ? "comment" : "comments/reviews") + " from "
                        + String.join(", ", who.stream().limit(4).toList()) + (who.size() > 4 ? ", ..." : ""));
            }
            if (mine && "failure".equals(w.ci)) {
                add(w, 35, "CI failing");
            }
            if (mine && w.conflicts) {
                add(w, 30, "merge conflicts");
            }
            if (w.mentionPending) {
                add(w, 30, "you were mentioned");
            }
            if (mine && !w.draft && "approved".equals(w.review) && !"failure".equals(w.ci) && !"pending".equals(w.ci)
                    && !w.conflicts) {
                add(w, 25, "approved, ready to merge");
            }
            if (mine && w.unresolvedThreads > 0 && w.activity.isEmpty()) {
                add(w, 5, w.unresolvedThreads + " unresolved thread" + (w.unresolvedThreads == 1 ? "" : "s"));
            }
        } else {
            if (w.due != null && w.due.isBefore(today)) {
                add(w, 40, "overdue (due " + w.due + ")");
            } else if (w.due != null && !w.due.isAfter(today.plusDays(2))) {
                add(w, 30, "due " + (w.due.equals(today) ? "today" : w.due.toString()));
            }
            String p = w.priority == null ? "" : w.priority.toLowerCase();
            if (p.contains("blocker") || p.contains("highest") || p.equals("urgent")) {
                add(w, 35, w.priority + " priority");
            } else if (p.contains("critical") || p.equals("high")) {
                add(w, 25, w.priority + " priority");
            } else if (p.contains("major")) {
                add(w, 10, null);
            }
            if (!w.activity.isEmpty()) {
                Set<String> who = new LinkedHashSet<>();
                w.activity.forEach(a -> who.add(a.author()));
                add(w, 35, w.activity.size() + " new comment" + (w.activity.size() == 1 ? "" : "s") + " from " + String.join(", ", who));
            }
        }
        if (w.updated != null && Duration.between(w.updated, Instant.now()).toDays() >= 14) {
            add(w, 5, "untouched for " + Duration.between(w.updated, Instant.now()).toDays() + " days");
        }
    }

    private static void add(WorkItem w, int points, String reason) {
        w.score += points;
        if (reason != null) {
            w.reasons.add(reason);
        }
    }

    /** Needs you, waiting on others, in progress. */
    static Map<String, List<WorkItem>> sections(List<WorkItem> items) {
        Comparator<WorkItem> order = Comparator.comparingInt((WorkItem w) -> w.score).reversed()
                .thenComparing(w -> w.updated == null ? Instant.EPOCH : w.updated, Comparator.reverseOrder());
        Map<String, List<WorkItem>> s = new LinkedHashMap<>();
        s.put("needs_you", new ArrayList<>());
        s.put("waiting_on_others", new ArrayList<>());
        s.put("in_progress", new ArrayList<>());
        for (WorkItem w : items) {
            if (w.score >= NEEDS_YOU) {
                s.get("needs_you").add(w);
            } else if (w.isPullRequest() && w.roles.contains(WorkItem.Role.AUTHOR)) {
                s.get("waiting_on_others").add(w);
            } else {
                s.get("in_progress").add(w);
            }
        }
        s.values().forEach(l -> l.sort(order));
        return s;
    }

    static String markdown(Map<String, List<WorkItem>> sections, Map<String, String> sources, int max, ZoneId zone) {
        StringBuilder sb = new StringBuilder("# Daily status — ").append(LocalDate.now(zone)).append("\n\n");
        section(sb, "Needs you", sections.get("needs_you"), max, true);
        section(sb, "Waiting on others", sections.get("waiting_on_others"), max, false);
        section(sb, "In progress", sections.get("in_progress"), max, false);
        List<String> problems = sources.entrySet().stream().filter(e -> !e.getValue().startsWith("ok"))
                .map(e -> e.getKey() + ": " + e.getValue()).toList();
        if (!problems.isEmpty()) {
            sb.append("**Sources not read:** ").append(String.join("; ", problems)).append("\n");
        }
        return sb.toString();
    }

    /** {@code reasons}: the section is about what needs me (PR state is in the reasons, the latest comment is quoted). */
    private static void section(StringBuilder sb, String title, List<WorkItem> items, int max, boolean reasons) {
        sb.append("## ").append(title).append(" (").append(items.size()).append(")\n\n");
        if (items.isEmpty()) {
            sb.append("Nothing.\n\n");
            return;
        }
        for (WorkItem w : items.subList(0, Math.min(max, items.size()))) {
            sb.append("- ").append(w.newSinceLastRun ? "**NEW** " : "").append('[').append(w.id).append("](").append(w.url)
                    .append(") ").append(w.title == null ? "" : w.title.replace("\n", " "));
            List<String> facts = new ArrayList<>();
            if (!w.reasons.isEmpty()) {
                facts.add(String.join(", ", w.reasons));
            }
            if (w.isPullRequest()) {
                facts.add(roles(w));
                if (!reasons) {
                    facts.add(prState(w));
                }
            } else {
                facts.add(w.state + (w.priority == null ? "" : ", " + w.priority));
            }
            if (w.updated != null) {
                facts.add("updated " + age(w.updated) + " ago");
            }
            sb.append(" — ").append(String.join(" · ", facts.stream().filter(f -> !f.isBlank()).toList())).append('\n');
            if (reasons && !w.activity.isEmpty()) {
                WorkItem.Activity a = w.activity.get(0);
                sb.append("  > ").append(a.author()).append(" (").append(a.kind()).append(", ").append(age(a.at())).append(" ago): ")
                        .append(a.excerpt()).append('\n');
            }
        }
        if (items.size() > max) {
            sb.append("- … ").append(items.size() - max).append(" more\n");
        }
        sb.append('\n');
    }

    static String roles(WorkItem w) {
        List<String> r = new ArrayList<>();
        if (w.roles.contains(WorkItem.Role.AUTHOR)) {
            r.add("yours");
        }
        if (w.roles.contains(WorkItem.Role.REVIEWER)) {
            r.add("reviewer");
        }
        if (w.roles.contains(WorkItem.Role.ASSIGNEE) && !w.roles.contains(WorkItem.Role.AUTHOR)) {
            r.add("assigned");
        }
        if (w.roles.contains(WorkItem.Role.MENTIONED)) {
            r.add("mentioned");
        }
        return String.join("/", r);
    }

    static String prState(WorkItem w) {
        List<String> p = new ArrayList<>();
        if (w.draft) {
            p.add("draft");
        }
        if (w.review != null) {
            p.add(w.review.replace('_', ' '));
        }
        if (w.ci != null) {
            p.add("CI " + w.ci);
        }
        if (w.unresolvedThreads > 0) {
            p.add(w.unresolvedThreads + " open thread" + (w.unresolvedThreads == 1 ? "" : "s"));
        }
        return String.join(", ", p);
    }

    static String age(Instant t) {
        if (t == null) {
            return "?";
        }
        long minutes = Math.max(0, ChronoUnit.MINUTES.between(t, Instant.now()));
        if (minutes < 60) {
            return minutes + "m";
        }
        if (minutes < 48 * 60) {
            return minutes / 60 + "h";
        }
        return minutes / (24 * 60) + "d";
    }
}
