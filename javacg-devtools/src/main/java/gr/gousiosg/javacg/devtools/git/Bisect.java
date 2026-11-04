package gr.gousiosg.javacg.devtools.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the first bad commit with {@code git bisect}, judging each commit with an oracle. Runs in
 * a temporary worktree detached at {@code bad}, so the user's checkout, index and bisect state are
 * left alone; the worktree is removed afterwards, also on failure or cancellation.
 */
public final class Bisect {

    public enum Verdict {
        GOOD, BAD, SKIP
    }

    public record Evaluation(Verdict verdict, String reason) {
    }

    /** Builds or tests the worktree at the current commit. */
    @FunctionalInterface
    public interface Oracle {
        Evaluation evaluate(Path worktree, String commit) throws Exception;
    }

    public record Step(String commit, String subject, Verdict verdict, double seconds, String reason) {
    }

    /**
     * @param firstBad   the first bad commit, or null when bisect could not decide
     * @param candidates when only skipped commits remain: the commits that could be the first bad one
     */
    public record Outcome(String firstBad, String subject, String author, String date, List<String> files, List<Step> steps,
            List<String> candidates, String message) {
    }

    /** Newer git quotes the term ("first 'bad' commit", seen with 2.56); older versions do not. */
    private static final Pattern FIRST_BAD = Pattern.compile("^([0-9a-f]{7,40}) is the first '?bad'? commit", Pattern.MULTILINE);
    private static final Pattern ROUGHLY = Pattern.compile("roughly (\\d+) steps?");

    private Bisect() {
    }

    public static Outcome run(Git repo, String good, String bad, Oracle oracle, int maxSteps, Consumer<String> progress,
            BooleanSupplier cancelled) throws IOException, InterruptedException {
        String goodCommit = repo.commit(good);
        String badCommit = repo.commit(bad);
        if (goodCommit.equals(badCommit)) {
            throw new IllegalArgumentException("good and bad are the same commit " + goodCommit.substring(0, 10));
        }
        Git.Result ancestor = repo.exec(Duration.ofSeconds(30), "merge-base", "--is-ancestor", goodCommit, badCommit);
        if (!ancestor.ok()) {
            throw new IllegalArgumentException(good + " is not an ancestor of " + bad + ": bisect needs good before bad");
        }
        Path base = Path.of(System.getProperty("java.io.tmpdir"), "javacg-mcp", "bisect");
        Files.createDirectories(base);
        Path tree = base.resolve(repo.dir().getFileName() + "-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")));
        repo.run("worktree", "add", "--detach", tree.toString(), badCommit);
        Git wt = new Git(tree);
        List<Step> steps = new ArrayList<>();
        try {
            String start = wt.run("bisect", "start", badCommit, goodCommit);
            Matcher r = ROUGHLY.matcher(start);
            String estimate = r.find() ? r.group(1) : "?";
            progress.accept("bisecting " + good + ".." + bad + ": about " + estimate + " steps, worktree " + tree);
            for (int i = 0; i < maxSteps; i++) {
                if (cancelled.getAsBoolean()) {
                    throw new InterruptedException("cancelled");
                }
                String commit = wt.run("rev-parse", "HEAD").strip();
                String subject = wt.run("show", "-s", "--format=%s", commit).strip();
                long t0 = System.nanoTime();
                Evaluation e;
                try {
                    e = oracle.evaluate(tree, commit);
                } catch (InterruptedException ex) {
                    throw ex;
                } catch (Exception ex) {
                    e = new Evaluation(Verdict.SKIP, "oracle failed: " + ex.getMessage());
                }
                double seconds = Math.round((System.nanoTime() - t0) / 1e8) / 10.0;
                steps.add(new Step(commit, subject, e.verdict(), seconds, e.reason()));
                progress.accept("step " + (i + 1) + "/~" + estimate + ": " + commit.substring(0, 10) + " "
                        + e.verdict().name().toLowerCase() + (e.reason() == null ? "" : " (" + e.reason() + ")"));
                Git.Result next = wt.exec(Duration.ofMinutes(2), "bisect", e.verdict().name().toLowerCase());
                String out = next.out() + next.err();
                Matcher fb = FIRST_BAD.matcher(out);
                if (fb.find()) {
                    return outcome(wt, wt.commit(fb.group(1)), steps);
                }
                if (out.contains("only 'skip'ped commits left")) {
                    List<String> candidates = new ArrayList<>();
                    Matcher h = Pattern.compile("^([0-9a-f]{40})$", Pattern.MULTILINE).matcher(out);
                    while (h.find()) {
                        candidates.add(h.group(1));
                    }
                    return new Outcome(null, null, null, null, List.of(), steps, candidates,
                            "Only skipped commits are left: the first bad commit is one of the candidates");
                }
                if (!next.ok()) {
                    throw new IOException("git bisect failed: " + out.strip());
                }
            }
            return new Outcome(null, null, null, null, List.of(), steps, List.of(), "Stopped after max_steps=" + maxSteps);
        } finally {
            cleanUp(repo, wt, tree);
        }
    }

    private static Outcome outcome(Git wt, String commit, List<Step> steps) throws IOException, InterruptedException {
        String[] info = wt.run("show", "-s", "--format=%s%n%an <%ae>%n%ad", "--date=iso", commit).split("\n", 3);
        List<String> files = wt.run("show", "--name-only", "--format=", commit).lines().filter(l -> !l.isBlank()).toList();
        return new Outcome(commit, info[0], info.length > 1 ? info[1] : null, info.length > 2 ? info[2].strip() : null, files,
                steps, List.of(), null);
    }

    private static void cleanUp(Git repo, Git wt, Path tree) {
        try {
            wt.exec(Duration.ofSeconds(30), "bisect", "reset");
        } catch (IOException | InterruptedException e) {
            // the worktree goes anyway
        }
        try {
            repo.exec(Duration.ofMinutes(1), "worktree", "remove", "--force", tree.toString());
            repo.exec(Duration.ofSeconds(30), "worktree", "prune");
        } catch (IOException | InterruptedException e) {
            // leave it for 'git worktree prune'
        }
    }

    /** {@code git bisect run} semantics: exit 0 good, 125 skip, anything else bad. */
    public static Oracle command(List<String> argv, Duration timeout) {
        return (tree, commit) -> {
            Process p = new ProcessBuilder(argv).directory(tree.toFile()).redirectErrorStream(true).start();
            p.getOutputStream().close();
            StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (var r = p.inputReader()) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (out.length() < 64 * 1024) {
                            out.append(line).append('\n');
                        }
                    }
                } catch (IOException e) {
                    // process gone
                }
            });
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                return new Evaluation(Verdict.SKIP, "timed out after " + timeout.toSeconds() + " s");
            }
            reader.join(2000);
            int exit = p.exitValue();
            String last = out.toString().strip().lines().reduce((a, b) -> b).orElse("");
            String reason = "exit " + exit + (last.isEmpty() ? "" : ": " + (last.length() > 200 ? last.substring(0, 200) : last));
            return new Evaluation(exit == 0 ? Verdict.GOOD : exit == 125 ? Verdict.SKIP : Verdict.BAD, reason);
        };
    }
}
