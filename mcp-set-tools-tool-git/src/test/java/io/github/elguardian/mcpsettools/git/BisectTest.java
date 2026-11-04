package io.github.elguardian.mcpsettools.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BisectTest {

    @Test
    void findsTheFirstBadCommit(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("repo");
        Files.createDirectories(dir);
        Git git = new Git(dir);
        git.run("init", "-q", "-b", "main");
        List<String> commits = new ArrayList<>();
        String[] states = { "ok", "ok 2", "ok 3", "BROKEN 4", "BROKEN 5 SKIPME", "BROKEN 6" };
        for (int i = 0; i < states.length; i++) {
            Files.writeString(dir.resolve("state.txt"), states[i] + "\n");
            git.run("add", "-A");
            git.run("-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", "commit " + (i + 1));
            commits.add(git.run("rev-parse", "HEAD").strip());
        }
        Bisect.Oracle oracle = Bisect.command(List.of("sh", "-c",
                "grep -q SKIPME state.txt && exit 125; ! grep -q BROKEN state.txt"), Duration.ofSeconds(30));
        List<String> progress = new ArrayList<>();
        Bisect.Outcome o = Bisect.run(git, commits.get(0), "main", oracle, 20, progress::add, () -> false);
        assertEquals(commits.get(3), o.firstBad());
        assertEquals("commit 4", o.subject());
        assertEquals(List.of("state.txt"), o.files());
        assertTrue(o.steps().size() >= 2 && o.steps().size() <= 4, o.steps().toString());
        assertTrue(progress.get(0).startsWith("bisecting "), progress.toString());
        // the user's checkout is untouched and the worktree is gone
        assertEquals(commits.get(5), git.run("rev-parse", "HEAD").strip());
        assertEquals("", git.run("status", "--porcelain"));
        assertEquals(1, git.run("worktree", "list").lines().count());
        assertTrue(git.exec(Duration.ofSeconds(10), "bisect", "log").exit() != 0, "no bisect in progress in the repo");

        assertThrows(IllegalArgumentException.class, () -> Bisect.run(git, "main", commits.get(0), oracle, 5, x -> { },
                () -> false));
    }
}
