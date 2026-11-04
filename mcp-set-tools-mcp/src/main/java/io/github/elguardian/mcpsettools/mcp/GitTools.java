package io.github.elguardian.mcpsettools.mcp;

import io.github.elguardian.mcpsettools.tool.Tool;
import io.github.elguardian.mcpsettools.tool.Schema;
import io.github.elguardian.mcpsettools.tool.ToolArgs;
import io.github.elguardian.mcpsettools.tool.ToolException;
import io.github.elguardian.mcpsettools.tool.Results;
import io.github.elguardian.mcpsettools.tool.Jobs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.elguardian.mcpsettools.devtools.git.Bisect;
import io.github.elguardian.mcpsettools.devtools.git.Git;
import io.github.elguardian.mcpsettools.devtools.git.SourceMapper;
import io.github.elguardian.mcpsettools.build.maven.MavenBuild;
import io.github.elguardian.mcpsettools.build.maven.MavenRunner;
import io.github.elguardian.mcpsettools.commons.stack.StackTrace;
import io.github.elguardian.mcpsettools.commons.stack.StackTraceParser;

/** Tools on a git repository: mapping stack traces to versions, bisecting regressions. */
final class GitTools {

    private final Jobs jobs;

    GitTools(Jobs jobs) {
        this.jobs = jobs;
    }

    List<Tool> tools() {
        return List.of(stacktraceMap(), bisect());
    }

    private Tool bisect() {
        return new Tool("bisect_run", "Find the commit that broke something", """
                Runs git bisect between a good and a bad commit and returns the first bad commit (subject, \
                author, files) and every step. Each commit is judged in a temporary worktree, so the user's \
                checkout is untouched. Oracles: maven (runs mvn with 'args'; bad when 'fails_test' fails, when the \
                log matches 'log_matches', or else when the build fails; commits that do not compile, or whose \
                build fails before the tests run, are skipped unless compile_errors_are_bad) or command (any \
                argv: exit 0 good, 125 skip, other bad, as git bisect run). Runs in the background: the reply is \
                a job_id for job_status. Use verify or test goals, not install, so the bisect builds do not \
                replace artifacts in the local Maven repository.""", false,
                Schema.object().string("good", "A commit, tag or branch known to be good", true)
                        .string("bad", "A commit known to be bad (default HEAD)", false)
                        .string("project_dir", "Maven project directory in the repository (default: the server's "
                                + "directory)", false)
                        .choice("oracle", "How each commit is judged", "maven", "maven", "command")
                        .strings("args", "maven: Maven arguments, e.g. [\"-pl\", \"core\", \"-am\", \"test\", "
                                + "\"-Dtest=OrderTest\", \"-Dsurefire.failIfNoSpecifiedTests=false\"]", false)
                        .string("fails_test", "maven: the commit is bad when this test fails (Class#method, Class.method or "
                                + "a part of it)", false)
                        .string("log_matches", "maven: the commit is bad when the build log matches this regex", false)
                        .bool("compile_errors_are_bad", "maven: count commits that do not compile as bad", false)
                        .bool("offline", "maven: add -o", false)
                        .strings("command", "command: the argv run in the worktree root", false)
                        .integer("max_steps", "Most commits judged", 30)
                        .integer("step_timeout_seconds", "Most time per commit (a timeout skips it)", 1800)
                        .tableFormat().build(),
                args -> {
                    Git git = git(args);
                    Path projectDir = ToolArgs.path(args.string("project_dir", "."));
                    if (!projectDir.startsWith(git.dir())) {
                        throw new ToolException("project_dir " + projectDir + " is not inside the repository " + git.dir());
                    }
                    Path relative = git.dir().relativize(projectDir);
                    java.time.Duration timeout = java.time.Duration.ofSeconds(args.integer("step_timeout_seconds", 1800));
                    Bisect.Oracle oracle = switch (args.string("oracle", "maven")) {
                        case "command" -> {
                            List<String> argv = args.strings("command");
                            if (argv.isEmpty()) {
                                throw new ToolException("oracle=command needs 'command'");
                            }
                            yield Bisect.command(argv, timeout);
                        }
                        default -> {
                            List<String> mvn = args.strings("args");
                            if (mvn.isEmpty()) {
                                throw new ToolException("oracle=maven needs 'args', e.g. [\"test\"]");
                            }
                            yield maven(relative, mvn, timeout, args);
                        }
                    };
                    String good = args.require("good");
                    String bad = args.string("bad", "HEAD");
                    int maxSteps = args.integer("max_steps", 30);
                    try {
                        git.commit(good);
                        git.commit(bad);
                    } catch (java.io.IOException e) {
                        throw new ToolException(e.getMessage());
                    }
                    Jobs.Job job = jobs.start("bisect_run", j -> {
                        Bisect.Outcome o;
                        try {
                            o = Bisect.run(git, good, bad, oracle, maxSteps, j::progress, j::cancelled);
                        } catch (IllegalArgumentException e) {
                            throw new ToolException(e.getMessage());
                        }
                        return outcome(o, args);
                    });
                    return Jobs.started(job, "The bisect");
                });
    }

    /** Runs Maven in the worktree and turns the build into a verdict. */
    private static Bisect.Oracle maven(Path relative, List<String> mvn, java.time.Duration timeout, ToolArgs args) {
        String failsTest = args.string("fails_test", null);
        java.util.regex.Pattern logMatches = args.has("log_matches") ? java.util.regex.Pattern.compile(args.require("log_matches"))
                : null;
        boolean compileBad = args.bool("compile_errors_are_bad", false);
        boolean offline = args.bool("offline", false);
        return (tree, commit) -> {
            Path log = java.nio.file.Files.createTempFile("mcp-set-tools-bisect-" + commit.substring(0, 10) + "-", ".log");
            MavenRunner.Run run = MavenRunner.run(new MavenRunner.Options(tree.resolve(relative.toString()), mvn, timeout, offline,
                    null, Map.of(), log, false));
            MavenBuild b = run.build();
            if (run.timedOut()) {
                return new Bisect.Evaluation(Bisect.Verdict.SKIP, "timed out");
            }
            if (!b.compilerErrors().isEmpty()) {
                MavenBuild.CompilerMessage c = b.compilerErrors().get(0);
                String where = c.file().substring(c.file().lastIndexOf('/') + 1) + ":" + c.line();
                return new Bisect.Evaluation(compileBad ? Bisect.Verdict.BAD : Bisect.Verdict.SKIP, "does not compile: " + where
                        + " " + c.message().lines().findFirst().orElse(""));
            }
            if (failsTest != null) {
                String want = failsTest.replace('#', '.');
                for (MavenBuild.TestFailure f : b.testFailures()) {
                    if (f.test().contains(want)) {
                        return new Bisect.Evaluation(Bisect.Verdict.BAD, f.test() + " " + f.kind());
                    }
                }
                if (b.testCounts().isEmpty() && run.exitCode() != 0) {
                    return new Bisect.Evaluation(Bisect.Verdict.SKIP, "the build failed before the tests ran"
                            + (b.goalFailures().isEmpty() ? "" : ": " + b.goalFailures().get(0).message()));
                }
                return new Bisect.Evaluation(Bisect.Verdict.GOOD, failsTest + " did not fail");
            }
            if (logMatches != null) {
                boolean found = logMatches.matcher(java.nio.file.Files.readString(run.log())).find();
                return new Bisect.Evaluation(found ? Bisect.Verdict.BAD : Bisect.Verdict.GOOD, found ? "log matches" : "no match");
            }
            if (run.exitCode() == 0) {
                return new Bisect.Evaluation(Bisect.Verdict.GOOD, "build passed");
            }
            String why = !b.testFailures().isEmpty() ? b.testFailures().get(0).test() + " " + b.testFailures().get(0).kind()
                    : !b.goalFailures().isEmpty() ? b.goalFailures().get(0).message() : "exit " + run.exitCode();
            return new Bisect.Evaluation(Bisect.Verdict.BAD, why);
        };
    }

    private static Map<String, Object> outcome(Bisect.Outcome o, ToolArgs args) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (o.firstBad() != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("commit", o.firstBad());
            c.put("subject", o.subject());
            c.put("author", o.author());
            c.put("date", o.date());
            c.put("files", o.files());
            m.put("first_bad", c);
        }
        if (!o.candidates().isEmpty()) {
            m.put("candidates", o.candidates());
        }
        if (o.message() != null) {
            m.put("message", o.message());
        }
        List<Map<String, Object>> steps = o.steps().stream().map(s -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("commit", s.commit().substring(0, 10));
            r.put("subject", s.subject());
            r.put("verdict", Results.lower(s.verdict()));
            r.put("seconds", s.seconds());
            r.put("reason", s.reason());
            return r;
        }).toList();
        m.put("steps", steps);
        Results.table(m, steps, null, args);
        return m;
    }

    private Tool stacktraceMap() {
        return new Tool("stacktrace_map", "Map a stack trace to source", """
                Maps the frames of a stack trace (from a customer log, a test failure) to the sources of a git \
                repository at a given version and checks each one: the line exists, is code, and lies in the \
                method the frame names. Shows each frame's file and surrounding lines at that version. Frames \
                that do not fit mean the trace comes from another version: give several 'versions' or a \
                'tag_pattern' (e.g. 7.4.*) to rank them and find which release produced the trace.""", true,
                Schema.object().string("text", "The stack trace", true)
                        .string("repo", "Git repository (default: the server's directory)", false)
                        .string("ref", "Version to map to: a tag, branch or commit (default HEAD)", false)
                        .strings("versions", "Several refs to rank instead of one", false)
                        .string("tag_pattern", "Rank the tags matching this glob, e.g. '7.4.*'", false)
                        .integer("max_refs", "Most refs ranked", 30)
                        .integer("context", "Source lines shown around each frame line", 2).build(),
                args -> {
                    StackTrace trace = StackTraceParser.parse(args.require("text"))
                            .orElseThrow(() -> new ToolException("No stack trace found in 'text'"));
                    Git git = git(args);
                    List<String> refs = new ArrayList<>(args.strings("versions"));
                    if (args.has("tag_pattern")) {
                        refs.addAll(git.tags(args.require("tag_pattern")));
                        if (refs.isEmpty()) {
                            throw new ToolException("No tag matches " + args.require("tag_pattern"));
                        }
                    }
                    int context = Math.max(0, args.integer("context", 2));
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("repo", git.dir().toString());
                    if (refs.isEmpty()) {
                        m.putAll(view(SourceMapper.map(git, args.string("ref", "HEAD"), trace, context)));
                        return m;
                    }
                    int max = args.integer("max_refs", 30);
                    if (refs.size() > max) {
                        m.put("note", "Ranked the first " + max + " of " + refs.size() + " refs (max_refs)");
                        refs = refs.subList(0, max);
                    }
                    List<SourceMapper.RefResult> ranked;
                    try {
                        ranked = SourceMapper.rank(git, refs, trace);
                    } catch (java.io.IOException e) {
                        throw new ToolException(e.getMessage());
                    }
                    m.put("candidates", ranked.stream().map(r -> {
                        Map<String, Object> c = new LinkedHashMap<>();
                        c.put("ref", r.ref());
                        c.put("commit", r.commit().substring(0, 10));
                        c.put("matching_frames", r.matching());
                        c.put("checked_frames", r.checked());
                        return c;
                    }).toList());
                    SourceMapper.RefResult best = ranked.get(0);
                    if (ranked.size() > 1 && ranked.get(1).score() == best.score() && ranked.get(1).matching() == best.matching()) {
                        m.put("best", "tie: " + ranked.stream().filter(r -> r.score() == best.score()
                                && r.matching() == best.matching()).map(SourceMapper.RefResult::ref).toList()
                                + " fit equally (the frames' files did not change between them)");
                    } else {
                        m.put("best", best.ref());
                    }
                    m.put("details", view(SourceMapper.map(git, best.ref(), trace, context)));
                    return m;
                });
    }

    static Git git(ToolArgs args) {
        Path dir = ToolArgs.path(args.string("repo", args.string("project_dir", ".")));
        Path top = Git.top(dir).orElseThrow(() -> new ToolException("Not a git repository: " + dir));
        return new Git(top);
    }

    private static Map<String, Object> view(SourceMapper.RefResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ref", r.ref());
        m.put("commit", r.commit().substring(0, 10));
        m.put("matching_frames", r.matching());
        m.put("checked_frames", r.checked());
        if (r.checked() == 0) {
            m.put("verdict", "No frame of the trace is in this repository");
        } else if (r.matching() == r.checked()) {
            m.put("verdict", "Every frame found in the repository fits " + r.ref());
        } else {
            m.put("verdict", (r.checked() - r.matching()) + " of " + r.checked() + " frames do not fit " + r.ref()
                    + ": the trace probably comes from another version");
        }
        List<Map<String, Object>> frames = new ArrayList<>();
        int outside = 0;
        for (SourceMapper.FrameResult f : r.frames()) {
            if (f.status() == SourceMapper.Status.NOT_FOUND) {
                outside++;
                continue;
            }
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("frame", f.frame().toString());
            x.put("status", Results.lower(f.status()));
            x.put("path", f.path() + (f.frame().line() > 0 ? ":" + f.frame().line() : ""));
            if (f.enclosing() != null) {
                x.put("in", f.enclosing());
            }
            if (f.note() != null) {
                x.put("note", f.note());
            }
            if (!f.context().isEmpty()) {
                x.put("source", String.join("\n", f.context()));
            }
            frames.add(x);
        }
        m.put("frames", frames);
        m.put("frames_outside_repo", outside);
        return m;
    }
}
