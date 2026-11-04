package io.github.elguardian.mcpsettools.build.tool;

import io.github.elguardian.mcpsettools.tool.Tool;
import io.github.elguardian.mcpsettools.tool.Schema;
import io.github.elguardian.mcpsettools.tool.ToolArgs;
import io.github.elguardian.mcpsettools.tool.ToolException;
import io.github.elguardian.mcpsettools.tool.Results;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.elguardian.mcpsettools.build.maven.MavenBuild;
import io.github.elguardian.mcpsettools.build.maven.MavenLogParser;
import io.github.elguardian.mcpsettools.build.maven.MavenRunner;
import io.github.elguardian.mcpsettools.build.maven.Reactor;
import io.github.elguardian.mcpsettools.commons.stack.StackTrace;
import io.github.elguardian.mcpsettools.commons.stack.StackTraceParser;
import io.github.elguardian.mcpsettools.commons.stack.StackTraces;

/** Maven: the reactor from the poms, running builds, reading build logs. */
final class MavenTools {

    List<Tool> tools() {
        return List.of(reactor(), run(), log());
    }

    private Tool reactor() {
        return new Tool("maven_reactor", "Inspect the Maven reactor", """
                Reads the poms of a multi-module Maven project without running Maven: every module with \
                its coordinates, packaging, directory and the reactor modules it depends on, in build order. \
                Answers what to build: 'projects' with also_make/also_make_dependents gives the modules of \
                mvn -pl ... -am/-amd, and 'changed_files' gives the modules owning those files plus their \
                dependents (what a change can break) with the -pl argument to build them. Only profiles \
                given in 'profiles' or activeByDefault count; exact=true asks Maven itself (mvn validate) \
                for the order, which also evaluates other profile activations.""", true,
                Schema.object().string("project_dir", "Directory of the root pom (default: the server's directory)",
                        false)
                        .strings("profiles", "Active profiles (a '!id' entry deactivates an activeByDefault one)", false)
                        .strings("projects", "Selected modules: artifactId, :artifactId, groupId:artifactId or path",
                                false)
                        .bool("also_make", "With projects: add the modules they depend on (-am)", false)
                        .bool("also_make_dependents", "With projects: add the modules depending on them (-amd)", false)
                        .strings("changed_files", "Changed files (relative to project_dir or absolute)", false)
                        .bool("exact", "Run 'mvn validate' and return the order Maven prints", false)
                        .tableFormat().build(),
                args -> {
                    Path dir = ToolArgs.path(args.string("project_dir", "."));
                    List<String> profiles = args.strings("profiles");
                    Reactor r;
                    try {
                        r = Reactor.load(dir, profiles);
                    } catch (java.nio.file.NoSuchFileException e) {
                        throw new ToolException("No pom.xml in " + dir);
                    }
                    List<Reactor.Module> order;
                    try {
                        order = r.buildOrder();
                    } catch (IllegalStateException e) {
                        throw new ToolException(e.getMessage());
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("root", r.root().toString());
                    List<Map<String, Object>> rows = order.stream().map(mod -> module(r, mod)).toList();
                    m.put("modules", rows);
                    if (!r.warnings().isEmpty()) {
                        m.put("warnings", r.warnings());
                    }
                    if (args.has("projects")) {
                        List<Reactor.Module> selected = new ArrayList<>();
                        for (String p : args.strings("projects")) {
                            try {
                                selected.add(r.find(p));
                            } catch (IllegalArgumentException e) {
                                throw new ToolException(e.getMessage());
                            }
                        }
                        List<Reactor.Module> built = r.select(selected, args.bool("also_make", false),
                                args.bool("also_make_dependents", false));
                        m.put("selection", selection(r, built, selected, args));
                    }
                    if (args.has("changed_files")) {
                        Set<Reactor.Module> owners = new LinkedHashSet<>();
                        List<String> outside = new ArrayList<>();
                        for (String f : args.strings("changed_files")) {
                            Path p = Path.of(f).isAbsolute() ? Path.of(f) : dir.resolve(f);
                            r.owner(p).ifPresentOrElse(owners::add, () -> outside.add(f));
                        }
                        Map<String, Object> a = new LinkedHashMap<>();
                        a.put("changed_modules", owners.stream().map(o -> o.gav().artifactId()).toList());
                        List<Reactor.Module> affected = r.select(owners, false, true);
                        a.put("affected_modules", affected.stream().map(o -> o.gav().artifactId()).toList());
                        if (!owners.isEmpty()) {
                            a.put("pl", r.plArgument(owners));
                            a.put("command", "mvn -pl " + r.plArgument(owners) + " -am -amd verify");
                        }
                        if (!outside.isEmpty()) {
                            a.put("outside_reactor", outside);
                        }
                        m.put("affected", a);
                    }
                    if (args.bool("exact", false)) {
                        List<String> mvnArgs = new ArrayList<>();
                        if (!profiles.isEmpty()) {
                            mvnArgs.add("-P" + String.join(",", profiles));
                        }
                        mvnArgs.add("validate");
                        MavenRunner.Run run = runMaven(dir, mvnArgs, Duration.ofMinutes(5), false, null, Map.of(), null,
                                false);
                        m.put("maven_order", run.build().reactorOrder());
                        if (run.exitCode() != 0) {
                            m.put("maven_errors", run.build().errors());
                        }
                    }
                    Results.table(m, rows.stream().map(row -> {
                        Map<String, Object> t = new LinkedHashMap<>(row);
                        t.remove("group_id");
                        t.remove("version");
                        return t;
                    }).toList(), null, args);
                    return m;
                });
    }

    private static Map<String, Object> selection(Reactor r, List<Reactor.Module> built, List<Reactor.Module> selected,
            ToolArgs args) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("modules", built.stream().map(x -> x.gav().artifactId()).toList());
        String flags = (args.bool("also_make", false) ? " -am" : "") + (args.bool("also_make_dependents", false) ? " -amd" : "");
        s.put("command", "mvn -pl " + r.plArgument(selected) + flags + " <goals>");
        return s;
    }

    private static Map<String, Object> module(Reactor r, Reactor.Module mod) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("artifact_id", mod.gav().artifactId());
        m.put("group_id", mod.gav().groupId());
        m.put("version", mod.gav().version());
        m.put("packaging", mod.packaging());
        String rel = r.root().relativize(mod.dir()).toString();
        m.put("path", rel.isEmpty() ? "." : rel);
        m.put("depends_on", mod.dependsOn().stream().map(k -> k.substring(k.indexOf(':') + 1)).toList());
        return m;
    }

    private Tool run() {
        return new Tool("maven_run", "Run Maven", """
                Runs Maven in a project (its mvnw if present, else mvn) with the given arguments, in batch \
                mode without download progress, and returns the parsed outcome instead of the raw output: \
                status, per-module status and time, compiler errors with file:line:column, failed tests \
                with their exception and location, the result blocks of surefire/failsafe, goal failures, \
                the -rf resume argument, grouped warnings, and the log file path for anything else \
                (log_query and maven_log read it). Lines carry timestamps and builder threads, so the \
                timeline tool can draw a parallel (-T) build. This executes the project's build: plugins \
                and tests run on this machine.""", false,
                Schema.object().strings("args", "Maven arguments: goals, phases and flags, e.g. [\"-pl\", \"core\", "
                        + "\"-am\", \"verify\"] or [\"test\", \"-Dtest=OrderTest\"]", true)
                        .string("project_dir", "Directory of the pom (default: the server's directory)", false)
                        .integer("timeout_seconds", "Kill the build after this many seconds", 1800)
                        .bool("offline", "Add -o", false)
                        .string("threads", "Parallel build: Maven -T value, e.g. 4 or 1C", false)
                        .raw("env", "Extra environment variables, e.g. {\"MAVEN_OPTS\": \"-Xmx2g\"}", false,
                                Map.of("type", "object", "additionalProperties", Map.of("type", "string")))
                        .string("log_file", "Where to write the output (default: a new file in the temp directory)", false)
                        .integer("tail_lines", "Last log lines returned when the build failed", 30)
                        .strings("app_packages", "Application package prefixes, for test failure locations", false)
                        .tableFormat().build(),
                args -> {
                    Path dir = ToolArgs.path(args.string("project_dir", "."));
                    Map<String, String> env = new LinkedHashMap<>();
                    if (args.raw("env") instanceof Map<?, ?> e) {
                        e.forEach((k, v) -> env.put(String.valueOf(k), String.valueOf(v)));
                    }
                    Path logFile = args.has("log_file") ? ToolArgs.path(args.require("log_file")) : null;
                    MavenRunner.Run run = runMaven(dir, args.strings("args"),
                            Duration.ofSeconds(args.integer("timeout_seconds", 1800)), args.bool("offline", false),
                            args.string("threads", null), env, logFile, true);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("command", String.join(" ", run.command()));
                    m.put("exit_code", run.exitCode());
                    if (run.timedOut()) {
                        m.put("timed_out", true);
                    }
                    m.put("seconds", Math.round(run.took().toMillis() / 100.0) / 10.0);
                    m.put("log_file", run.log().toString());
                    m.putAll(view(run.build(), dir, args));
                    if (run.exitCode() != 0) {
                        m.put("log_tail", tail(run.log(), args.integer("tail_lines", 30)));
                        List<String> reports = reportDirs(dir, run.build());
                        if (!reports.isEmpty()) {
                            m.put("test_reports", reports);
                        }
                    }
                    return m;
                });
    }

    private Tool log() {
        return new Tool("maven_log", "Read a Maven build log", """
                Parses Maven console output from a file or pasted text (any Maven 3.9/4 batch output, with or \
                without timestamps): status, modules with status and time, compiler errors and warnings, \
                failed tests with stack traces, goal failures, the -rf resume argument, warnings grouped by \
                message template, and remaining errors.""", true,
                Schema.object().string("file", "Log file", false).string("text", "Log text, when there is no file", false)
                        .string("project_dir", "Project root, to show source paths relative to it", false)
                        .strings("app_packages", "Application package prefixes, for test failure locations", false)
                        .tableFormat().build(),
                args -> {
                    MavenBuild b;
                    if (args.has("file")) {
                        Path f = ToolArgs.path(args.require("file"));
                        if (!Files.isRegularFile(f)) {
                            throw new ToolException("Not a file: " + f);
                        }
                        b = MavenLogParser.parse(f);
                    } else if (args.has("text")) {
                        b = MavenLogParser.parse(args.require("text"));
                    } else {
                        throw new ToolException("Give 'file' or 'text'");
                    }
                    return view(b, args.has("project_dir") ? ToolArgs.path(args.require("project_dir")) : null, args);
                });
    }

    static MavenRunner.Run runMaven(Path dir, List<String> args, Duration timeout, boolean offline, String threads,
            Map<String, String> env, Path log, boolean timestamps) throws IOException, InterruptedException {
        try {
            return MavenRunner.run(new MavenRunner.Options(dir, args, timeout, offline, threads, env, log, timestamps));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ToolException(e.getMessage());
        }
    }

    /** The structured view of a build shared by maven_run and maven_log. */
    static Map<String, Object> view(MavenBuild b, Path projectDir, ToolArgs args) {
        List<String> app = args.strings("app_packages");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", Results.lower(b.status()));
        if (b.totalSeconds() >= 0) {
            m.put("total_seconds", b.totalSeconds());
        }
        List<Map<String, Object>> modules = new ArrayList<>();
        for (MavenBuild.Module mod : b.modules()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("module", mod.artifactId() != null ? mod.artifactId() : mod.name());
            if (mod.name() != null && !mod.name().equals(r.get("module"))) {
                r.put("name", mod.name());
            }
            r.put("status", mod.status() == null ? "" : mod.status().toLowerCase(java.util.Locale.ROOT));
            r.put("seconds", mod.seconds() >= 0 ? mod.seconds() : null);
            if (mod.thread() != null && !mod.thread().equals("main")) {
                r.put("thread", mod.thread());
            }
            modules.add(r);
        }
        m.put("modules", modules);
        if (!b.compilerErrors().isEmpty()) {
            m.put("compiler_errors", b.compilerErrors().stream().limit(50).map(c -> compiler(c, projectDir)).toList());
        }
        if (!b.compilerWarnings().isEmpty()) {
            m.put("compiler_warnings", Results.page(b.compilerWarnings().stream().map(c -> compiler(c, projectDir)).toList(),
                    20));
        }
        if (!b.testFailures().isEmpty()) {
            m.put("test_failures", b.testFailures().stream().limit(50).map(f -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("module", f.module());
                r.put("test", f.test());
                r.put("kind", f.kind());
                r.put("seconds", f.seconds());
                StackTrace t = StackTraceParser.parse(f.trace()).orElse(null);
                if (t != null) {
                    r.put("exception", StackTraces.oneLine(t, app));
                    r.put("stack_trace", StackTraces.render(t, true, app));
                } else if (!f.trace().isEmpty()) {
                    r.put("output", cut(f.trace(), 1000));
                }
                return r;
            }).toList());
        }
        if (!b.testSummary().isEmpty()) {
            m.put("test_summary", b.testSummary());
        }
        List<Map<String, Object>> totals = b.testCounts().stream().filter(c -> c.testClass() == null).map(c -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("module", c.module());
            r.put("run", c.run());
            r.put("failures", c.failures());
            r.put("errors", c.errors());
            r.put("skipped", c.skipped());
            return r;
        }).toList();
        if (!totals.isEmpty()) {
            m.put("test_totals", totals);
        }
        if (!b.goalFailures().isEmpty()) {
            m.put("goal_failures", b.goalFailures().stream().map(g -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("module", g.module());
                if (g.plugin() != null) {
                    r.put("goal", g.plugin() + " (" + g.execution() + ")");
                }
                r.put("message", g.message());
                return r;
            }).toList());
        }
        if (b.resumeFrom() != null) {
            m.put("resume_with", "-rf " + b.resumeFrom());
        }
        if (!b.warnings().isEmpty()) {
            m.put("warnings", Results.page(b.warnings().stream().map(w -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("count", w.count());
                r.put("message", w.count() == 1 ? w.example() : w.template());
                if (w.module() != null) {
                    r.put("module", w.module());
                }
                return r;
            }).toList(), 20));
        }
        if (!b.errors().isEmpty()) {
            m.put("other_errors", Results.page(b.errors(), 30));
        }
        Results.table(m, modules, List.of("module", "status", "seconds"), args);
        return m;
    }

    private static Map<String, Object> compiler(MavenBuild.CompilerMessage c, Path projectDir) {
        Map<String, Object> r = new LinkedHashMap<>();
        Path f = Path.of(c.file());
        r.put("file", projectDir != null && f.startsWith(projectDir) ? projectDir.relativize(f).toString() : c.file());
        r.put("line", c.line());
        r.put("column", c.column());
        r.put("message", c.message());
        if (c.module() != null) {
            r.put("module", c.module());
        }
        return r;
    }

    /** Report directories of the modules whose tests failed, for junit_failures. */
    private static List<String> reportDirs(Path dir, MavenBuild b) {
        Set<String> failing = new LinkedHashSet<>();
        b.testFailures().forEach(f -> failing.add(f.module()));
        b.testCounts().stream().filter(c -> c.failures() + c.errors() > 0).forEach(c -> failing.add(c.module()));
        failing.remove(null);
        if (failing.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        try {
            Reactor r = Reactor.load(dir, List.of());
            for (String module : failing) {
                try {
                    Path mdir = r.find(module).dir();
                    for (String d : List.of("target/surefire-reports", "target/failsafe-reports")) {
                        if (Files.isDirectory(mdir.resolve(d))) {
                            result.add(mdir.resolve(d).toString());
                        }
                    }
                } catch (IllegalArgumentException e) {
                    // not a module of the static reactor
                }
            }
        } catch (IOException e) {
            // no reports then
        }
        return result;
    }

    private static String tail(Path log, int lines) {
        try {
            List<String> all = Files.readAllLines(log);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException e) {
            return "";
        }
    }

    private static String cut(String s, int max) {
        if (s == null || max <= 0 || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "… [" + (s.length() - max) + " more characters]";
    }
}
