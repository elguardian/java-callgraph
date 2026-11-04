package io.github.elguardian.mcpsettools.devtools.threads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ThreadDumpTest {

    static List<ThreadDump> dumps(String name) throws Exception {
        Path p = Path.of(ThreadDumpTest.class.getResource("/threads/" + name).toURI());
        return ThreadDumpParser.parse(name, Files.readString(p));
    }

    @Test
    void parsesJdk25Dumps() throws Exception {
        List<ThreadDump> dumps = dumps("three-dumps.txt");
        assertEquals(3, dumps.size());
        ThreadDump d = dumps.get(0);
        assertEquals("2026-11-04 19:42:25", d.timestamp());
        ThreadInfo a = d.thread("dl-a");
        assertEquals(34, a.id());
        assertEquals("BLOCKED", a.state());
        assertEquals("on object monitor", a.detail());
        assertEquals(0.20, a.cpuMillis(), 1e-9);
        assertEquals(ThreadInfo.Lock.Kind.WAITING_TO_LOCK, a.waitingFor().kind());
        assertEquals("0x0000000636419440", a.held().get(0).address());
        assertEquals("d.Stuck.lambda$main$0", a.frames().get(0).location());
        ThreadInfo jl1 = d.thread("jl-1");
        assertEquals(ThreadInfo.Lock.Kind.PARKING, jl1.waitingFor().kind());
        assertEquals("0x0000000636419578", jl1.ownable().get(0).address());
        assertEquals(2, d.deadlocks().size());
        // the deadlock section repeats stacks: they are not extra threads
        assertEquals(1, d.threads().stream().filter(t -> t.name().equals("dl-a")).count());
        assertTrue(d.thread("GC Thread#0") != null && !d.thread("GC Thread#0").isJavaThread());
    }

    @Test
    void deadlocksContentionStuckAndHot() throws Exception {
        ThreadDumpAnalysis a = new ThreadDumpAnalysis(dumps("three-dumps.txt"), List.of("d"));
        List<ThreadDumpAnalysis.Deadlock> deadlocks = a.deadlocks();
        assertEquals(Set.of(Set.of("dl-a", "dl-b"), Set.of("jl-1", "jl-2")),
                Set.copyOf(deadlocks.stream().map(x -> Set.copyOf(x.threads())).toList()));
        assertTrue(deadlocks.stream().allMatch(ThreadDumpAnalysis.Deadlock::reportedByJvm));
        ThreadDumpAnalysis.Contention top = a.contention().get(0);
        assertEquals(3, top.waiters().size());
        assertEquals("holder", top.owner());
        assertEquals("RUNNABLE", top.ownerState());
        assertEquals("d.Stuck.spin", top.ownerFrames().get(0).location());
        List<ThreadDumpAnalysis.Group> busy = a.groups(3).stream().filter(g -> !g.idle()).toList();
        assertEquals(List.of("worker-39", "worker-40", "worker-41"), busy.get(0).threads());
        assertEquals("BLOCKED", busy.get(0).state());
        assertTrue(a.stuck(5).stream().anyMatch(s -> s.thread().equals("dl-a")));
        assertTrue(a.stuck(5).stream().noneMatch(s -> s.thread().equals("main")), "sleeping main is not stuck");
        ThreadDumpAnalysis.Hot hot = a.hot(3).get(0);
        assertEquals("holder", hot.thread());
        assertEquals(21451.46 - 2192.45, hot.cpuMillis(), 1e-6);
        assertTrue(a.pools().stream().anyMatch(p -> p.name().equals("worker-*") && p.threads() == 3));
    }

    @Test
    void jdk8DumpInsideAConsoleLog() throws Exception {
        List<ThreadDump> dumps = dumps("jdk8-console.log");
        assertEquals(1, dumps.size());
        ThreadDump d = dumps.get(0);
        assertEquals("2024-05-01 10:20:00", d.timestamp());
        assertEquals(5, d.threads().size());
        ThreadDumpAnalysis a = new ThreadDumpAnalysis(dumps, List.of("org.acme"));
        ThreadDumpAnalysis.Contention c = a.contention().get(0);
        assertEquals("org.acme.Cache@0x00000000c1a2b3c4", c.lock());
        assertEquals("default task-7", c.owner());
        assertEquals("java.net.SocketInputStream.socketRead0", c.ownerFrames().get(0).location());
        assertEquals(List.of("default task-13", "default task-12"), c.waiters());
        assertTrue(a.deadlocks().isEmpty());
        assertTrue(a.groups(3).stream().filter(g -> g.threads().contains("default task-3")).findFirst().orElseThrow().idle());
        assertTrue(a.hot(5).isEmpty(), "one dump: no CPU deltas");
    }
}
