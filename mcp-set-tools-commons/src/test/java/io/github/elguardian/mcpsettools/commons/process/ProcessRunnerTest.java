package io.github.elguardian.mcpsettools.commons.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessRunnerTest {

    @Test
    void outputExitTimeoutAndCancel(@TempDir Path tmp) throws Exception {
        ProcessRunner.Result r = ProcessRunner.of("sh", "-c", "echo out; echo err >&2; exit 3").run();
        assertEquals(3, r.exit());
        assertEquals("out\n", r.stdout());
        assertEquals("err\n", r.stderr());
        ProcessRunner.Result merged = ProcessRunner.of("sh", "-c", "echo a; echo b >&2").mergeErrors().run();
        assertTrue(merged.stdout().contains("a") && merged.stdout().contains("b"));
        Path log = tmp.resolve("log.txt");
        assertEquals(0, ProcessRunner.of("sh", "-c", "echo $X").env("X", "hello").outputTo(log).directory(tmp).run().exit());
        assertEquals("hello\n", Files.readString(log));
        ProcessRunner.Result slow = ProcessRunner.of("sleep", "30").timeout(Duration.ofMillis(300)).run();
        assertTrue(slow.timedOut());
        assertEquals(-1, slow.exit());
        long t0 = System.nanoTime();
        ProcessRunner.Result cancelled = ProcessRunner.of("sleep", "30").cancelledWhen(() -> System.nanoTime() - t0 > 300_000_000L).run();
        assertTrue(cancelled.cancelled() && !cancelled.timedOut());
    }
}
