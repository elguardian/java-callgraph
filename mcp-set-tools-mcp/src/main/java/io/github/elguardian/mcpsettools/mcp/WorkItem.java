package io.github.elguardian.mcpsettools.mcp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** One piece of pending work: a Jira issue, a GitHub pull request or a GitLab merge request. */
final class WorkItem {

    enum Role {
        AUTHOR, REVIEWER, ASSIGNEE, MENTIONED
    }

    /** A comment, review or approval by someone else after my last action. */
    record Activity(String author, Instant at, String kind, String excerpt) {
    }

    final String source;
    final String instance;
    final String id;
    String title;
    String url;
    final Set<Role> roles = EnumSet.noneOf(Role.class);
    String state;
    String priority;
    String type;
    LocalDate due;
    Instant created;
    Instant updated;
    boolean draft;
    /** success, failure, pending, or null. */
    String ci;
    /** approved, changes_requested, review_required, or null. */
    String review;
    boolean conflicts;
    int unresolvedThreads;
    /** GitLab detailed merge status, GitHub mergeable state. */
    String mergeStatus;
    /** I was asked to review and have not reviewed since the last push. */
    boolean reviewPending;
    /** Someone mentioned me (GitLab todo, GitHub mentions search) and I have not acted since. */
    boolean mentionPending;
    final List<Activity> activity = new ArrayList<>();
    Instant myLastAction;
    Instant lastPush;
    boolean newSinceLastRun;
    final List<String> reasons = new ArrayList<>();
    int score;

    WorkItem(String source, String instance, String id) {
        this.source = source;
        this.instance = instance;
        this.id = id;
    }

    String key() {
        return source + ":" + instance + ":" + id;
    }

    boolean isPullRequest() {
        return !source.equals("jira");
    }
}
