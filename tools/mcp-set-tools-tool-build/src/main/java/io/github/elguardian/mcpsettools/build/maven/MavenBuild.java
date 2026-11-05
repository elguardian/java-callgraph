package io.github.elguardian.mcpsettools.build.maven;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What a Maven build log says, see {@link MavenLogParser}. */
public final class MavenBuild {

    public enum Status {
        SUCCESS, FAILURE, UNKNOWN
    }

    /**
     * One module of the reactor.
     *
     * @param status    SUCCESS, FAILURE or SKIPPED from the reactor summary; null if the log has none
     * @param seconds   time from the reactor summary, or -1
     * @param start     first timestamped line of the module (epoch µs, 1970-01-01 based for time-only
     *                  timestamps), or Long.MIN_VALUE
     * @param thread    builder thread ({@code mvn-builder-x} with -T), if the log shows threads
     */
    public record Module(String artifactId, String name, int index, String status, double seconds, long start, long end,
            String thread, List<String> executions) {
    }

    /** A compiler error or warning: {@code /path/File.java:[line,col] message}. */
    public record CompilerMessage(String severity, String module, String file, int line, int column, String message) {
    }

    /** A test reported {@code <<< FAILURE!} or {@code <<< ERROR!} by surefire/failsafe, with its stack trace. */
    public record TestFailure(String module, String test, String kind, double seconds, String trace) {
    }

    /** {@code Tests run: n, Failures: f, Errors: e, Skipped: s} for a module (the results line) or a class. */
    public record TestCounts(String module, String testClass, int run, int failures, int errors, int skipped) {
    }

    /** {@code Failed to execute goal <plugin> (<execution>) on project <module>: <message>}. */
    public record GoalFailure(String plugin, String execution, String module, String message) {
    }

    /** Repeated log lines with their variable parts replaced by {@code <*>}. */
    public record Repeated(String template, int count, String example, String module) {
    }

    Status status = Status.UNKNOWN;
    String totalTime;
    double totalSeconds = -1;
    String finishedAt;
    String resumeFrom;
    final Map<String, Module> modules = new LinkedHashMap<>();
    final List<String> reactorOrder = new ArrayList<>();
    final List<CompilerMessage> compilerErrors = new ArrayList<>();
    final List<CompilerMessage> compilerWarnings = new ArrayList<>();
    final List<TestFailure> testFailures = new ArrayList<>();
    final List<String> testSummary = new ArrayList<>();
    final List<TestCounts> testCounts = new ArrayList<>();
    final List<GoalFailure> goalFailures = new ArrayList<>();
    final List<Repeated> warnings = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    long lines;

    public Status status() {
        return status;
    }

    public String totalTime() {
        return totalTime;
    }

    public double totalSeconds() {
        return totalSeconds;
    }

    public String finishedAt() {
        return finishedAt;
    }

    /** The {@code -rf :module} Maven suggests to resume the build, or null. */
    public String resumeFrom() {
        return resumeFrom;
    }

    /** Project names (with packaging) from the "Reactor Build Order" block Maven prints first. */
    public List<String> reactorOrder() {
        return reactorOrder;
    }

    /** Modules in reactor order. */
    public List<Module> modules() {
        return List.copyOf(modules.values());
    }

    public List<CompilerMessage> compilerErrors() {
        return compilerErrors;
    }

    public List<CompilerMessage> compilerWarnings() {
        return compilerWarnings;
    }

    public List<TestFailure> testFailures() {
        return testFailures;
    }

    /** The entries of surefire's {@code Failures:} / {@code Errors:} / {@code Flakes:} result blocks. */
    public List<String> testSummary() {
        return testSummary;
    }

    /** Module totals (testClass null) and per-class counts of classes that failed. */
    public List<TestCounts> testCounts() {
        return testCounts;
    }

    public List<GoalFailure> goalFailures() {
        return goalFailures;
    }

    /** [WARNING] lines other than compiler warnings, grouped. */
    public List<Repeated> warnings() {
        return warnings;
    }

    /** [ERROR] lines not covered by the other lists, without Maven's help boilerplate. */
    public List<String> errors() {
        return errors;
    }

    public long lines() {
        return lines;
    }
}
