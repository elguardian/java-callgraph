package io.github.elguardian.mcpsettools.core.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.elguardian.mcpsettools.core.MethodId;
import io.github.elguardian.mcpsettools.rt.Probe;
import io.github.elguardian.mcpsettools.rt.Recorder;
import io.github.elguardian.mcpsettools.rt.Symbols;

/** Drives the probe runtime in-process and reads the result back. */
class RecordingTest {

    @TempDir
    Path tmp;

    @Test
    void writesAndReadsBackAThreadAwareTrace() throws Exception {
        Recorder.configure(tmp.toString(), "tostring", false);
        int a = Symbols.method("p/A", "a", "()V");
        int b = Symbols.method("p/A", "b", "(Ljava/lang/String;)V");
        int c = Symbols.method("p/A", "c", "()V");
        Symbols.line(a, 10);
        String dir = Recorder.start("unit test");
        assertNotNull(dir);

        Probe.enter(a);
        for (int i = 0; i < 5000; i++) { // several buffer flushes
            Probe.enter(b);
            Probe.args(new Object[] {"x\ny=1"}, b);
            Probe.exit(b);
        }
        Probe.enter(c);
        Probe.exitThrow(new IllegalStateException(), c);
        Probe.exit(a);
        Probe.exit(a); // unmatched: ignored
        Thread t = new Thread(() -> {
            Probe.enter(c);
            Probe.exit(c);
        }, "worker");
        t.start();
        t.join();
        Probe.enter(a); // left open
        String done = Recorder.stop();
        assertEquals(dir, done);
        Probe.enter(c); // after stop: dropped

        Recording r = Recording.open(Path.of(done));
        assertTrue(r.isComplete());
        assertEquals("unit test", r.label());
        assertEquals(10, r.firstLine(a));
        assertTrue(r.threads().containsValue("worker"));

        TraceSummary s = TraceSummary.of(r);
        MethodId ma = MethodId.of("p/A", "a", "()V");
        MethodId mb = MethodId.of("p/A", "b", "(Ljava/lang/String;)V");
        MethodId mc = MethodId.of("p/A", "c", "()V");
        assertEquals(5000, s.graph().edge(ma, mb).runtimeCount());
        assertEquals(1, s.graph().edge(ma, mc).runtimeCount());
        assertEquals(2, s.stats().get(mc).calls());
        assertEquals(1L, s.stats().get(mc).exceptions().get("java.lang.IllegalStateException"));
        assertEquals(2, s.stats().get(ma).calls());
        assertEquals(1, s.unmatchedExits());

        StringWriter text = new StringWriter();
        TraceText.write(r, text);
        List<String> lines = text.toString().lines().toList();
        assertTrue(lines.get(0).matches(">\\[0]\\[\\d+]p\\.A:a=\\d+"), lines.get(0));
        assertTrue(lines.get(1).matches(">\\[1]\\[\\d+]p\\.A:b \\[x\\\\ny\\\\u003d1]=\\d+"), lines.get(1));
        assertTrue(lines.stream().anyMatch(l -> l.matches("<\\[1]\\[\\d+]p\\.A:c!java\\.lang\\.IllegalStateException=\\d+")));
        // every line keeps the parseable shape of the original format
        assertTrue(lines.stream().allMatch(l -> l.matches("[<>]\\[\\d+]\\[\\d+][^=]+=\\d+")));
    }

    @Test
    void unwindsFramesWhoseExitWasNeverRecorded() throws Exception {
        CallStacks.Exit[] seen = new CallStacks.Exit[3];
        CallStacks stacks = new CallStacks(new CallStacks.Listener() {
            @Override
            public void onExit(long thread, CallStacks.Frame frame, List<CallStacks.Frame> stack, CallStacks.Exit how,
                    long exitNanos, int exceptionClass) {
                seen[frame.method() - 1] = how;
            }
        });
        stacks.enter(1, 1, 0);
        stacks.enter(1, 2, 1);
        stacks.enter(1, 3, 2); // e.g. a constructor that threw before super()
        stacks.exitThrow(1, 2, 0, 3);
        stacks.end();
        assertEquals(CallStacks.Exit.UNWOUND, seen[2]);
        assertEquals(CallStacks.Exit.EXCEPTION, seen[1]);
        assertEquals(CallStacks.Exit.OPEN, seen[0]);
        assertEquals(1, stacks.unwoundFrames());
    }
}
