package gr.gousiosg.javacg.analyze.detect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import gr.gousiosg.javacg.analyze.Model;
import gr.gousiosg.javacg.core.MethodId;
import gr.gousiosg.javacg.rt.Probe;
import gr.gousiosg.javacg.rt.Recorder;
import gr.gousiosg.javacg.rt.Symbols;
import gr.gousiosg.javacg.stat.StaticAnalyzer;
import gr.gousiosg.javacg.stat.StaticResult;
import gr.gousiosg.javacg.testkit.TestCompiler;

/**
 * Each fixture method is named after what the detectors must say about it: methods whose name
 * starts with {@code bad} must get exactly the expected rule, {@code ok} methods nothing.
 */
class DetectorsTest {

    private static final String SOURCE = """
            package d;

            import java.io.*;
            import java.nio.file.*;
            import java.util.concurrent.locks.*;

            public class Fixture {
                // ---- nulls
                int badDefiniteNull() { String s = null; return s.length(); }
                int badCheckedNull(String s) { if (s == null) { System.out.println("no s"); } return s.length(); }
                int badPossibleNull(boolean f) { String s = null; if (f) { s = "x"; } return s.length(); }
                int okEarlyReturn(String s) { if (s == null) { return 0; } return s.length(); }
                int okShortCircuit(String s) { return s != null && s.isEmpty() ? 1 : 0; }
                int okReassigned() { String s = null; s = "a"; return s.length(); }
                int okNeverReturns(String s) { if (s == null) { fail(); } return s.length(); }
                static void fail() { throw new IllegalStateException(); }
                int okUnknownParam(String s) { int n = s.length(); return s == null ? -1 : n; }

                // ---- resources
                Closeable field;
                int badLeakOnEarlyReturn(File f) throws IOException {
                    FileInputStream in = new FileInputStream(f);
                    int b = in.read();
                    if (b < 0) { return -1; }
                    in.close();
                    return b;
                }
                long badFactoryLeak(Path p) throws IOException { return Files.lines(p).count(); }
                int badNoFinally(File f) throws IOException {
                    FileInputStream in = new FileInputStream(f);
                    int b = in.read();
                    in.close();
                    return b;
                }
                int okTryWithResources(File f) throws IOException {
                    try (FileInputStream in = new FileInputStream(f)) { return in.read(); }
                }
                int okFinally(File f) throws IOException {
                    FileInputStream in = null;
                    try { in = new FileInputStream(f); return in.read(); }
                    finally { if (in != null) { in.close(); } }
                }
                InputStream okReturned(File f) throws IOException { return new FileInputStream(f); }
                void okStored(File f) throws IOException { field = new FileInputStream(f); }
                BufferedReader okWrapsCallersReader(Reader r) { return new BufferedReader(r); }
                String okInMemory() { StringWriter w = new StringWriter(); new PrintWriter(w).print("x"); return w.toString(); }
                String okWrappedAndClosed(File f) throws IOException {
                    BufferedReader r = new BufferedReader(new FileReader(f));
                    try { return r.readLine(); } finally { r.close(); }
                }
                Object okNestedTryWithResourcesAndCatch(File f) {
                    try (InputStream in = getClass().getResourceAsStream(f.getName())) {
                        if (in == null) { return null; }
                        return new DataInputStream(in).readUTF();
                    } catch (IOException | RuntimeException e) {
                        System.err.println(e);
                        return null;
                    }
                }
                int okDecoratorChain(Path p) throws IOException {
                    try (InputStream file = Files.newInputStream(p);
                            DataInputStream in = new DataInputStream(new BufferedInputStream(file))) {
                        return in.readInt();
                    }
                }
                DataOutputStream okClosedOnFailure(Path p) throws IOException {
                    OutputStream file = Files.newOutputStream(p);
                    try {
                        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(file));
                        out.writeInt(42);
                        return out;
                    } catch (IOException | RuntimeException e) {
                        file.close();
                        throw e;
                    }
                }
                DataOutputStream badHeaderWriteLeaks(Path p) throws IOException {
                    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(p)));
                    out.writeInt(42);
                    return out;
                }

                // ---- exceptions
                void badSwallowed() { try { Integer.parseInt("x"); } catch (NumberFormatException e) { } }
                void badBroadSwallowed() { try { work(); } catch (Exception e) { } }
                void badInterruptIgnored() { try { Thread.sleep(1); } catch (InterruptedException e) { System.out.println("x"); } }
                void badCauseLost() { try { work(); } catch (IllegalStateException e) { throw new RuntimeException("failed"); } }
                void okIgnoredByName() { try { work(); } catch (Exception ignored) { work(); } }
                void okLogged() { try { work(); } catch (Exception e) { System.err.println(e); } }
                void okInterruptRestored() { try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
                void okCauseKept() { try { work(); } catch (IllegalStateException e) { throw new RuntimeException("failed", e); } }
                void work() { }

                // ---- locks
                final Object a = new Object();
                final Object b = new Object();
                void lockAB() { synchronized (a) { synchronized (b) { work(); } } }
                void lockBA() { synchronized (b) { lockAOnly(); } }
                void lockAOnly() { synchronized (a) { work(); } }
                static void badTransfer(Account from, Account to) { synchronized (from) { synchronized (to) { from.n--; to.n++; } } }
                final ReentrantLock l1 = new ReentrantLock();
                void okSameOrderTwice() { l1.lock(); try { synchronized (a) { work(); } } finally { l1.unlock(); } }

                // ---- recursion and dead code
                static boolean isEven(int n) { return n == 0 || isOdd(n - 1); }
                static boolean isOdd(int n) { return n != 0 && isEven(n - 1); }
                private void badUnusedPrivate() { }
                private void okUsedPrivate() { }
                private void okReferencedPrivate() { }
                void callers() { okUsedPrivate(); Runnable r = this::okReferencedPrivate; r.run(); }
            }

            class Account { int n; }
            """;

    static StaticResult statics;
    static Model staticOnly;
    static Model withRuntime;
    static Path tmp;

    @BeforeAll
    static void setUp(@TempDir Path dir) throws Exception {
        tmp = dir;
        Path jar = TestCompiler.create().source("d.Fixture", SOURCE).compile().writeJar(dir.resolve("d.jar"));
        statics = StaticAnalyzer.create().app(jar).run();
        staticOnly = Model.of(statics, List.of());

        Recorder.configure(dir.resolve("out").toString(), "none", false);
        Recorder.start("detectors");
        // NPE escaping badCheckedNull
        int checked = id("badCheckedNull", "(Ljava/lang/String;)I");
        Probe.enter(checked);
        Probe.exitThrow(new NullPointerException(), checked);
        // deep recursion
        int even = id("isEven", "(I)Z");
        for (int i = 0; i < 250; i++) {
            Probe.enter(even);
        }
        for (int i = 0; i < 250; i++) {
            Probe.exit(even);
        }
        // runtime lock-order inversion on two threads
        Object lockA = new Object();
        Object lockB = new Object();
        int lockAB = id("lockAB", "()V");
        int lockBA = id("lockBA", "()V");
        Thread t1 = new Thread(() -> { Probe.enter(lockAB); Probe.lock(lockA); Probe.lock(lockB); Probe.unlock(lockB);
            Probe.unlock(lockA); Probe.exit(lockAB); }, "t1");
        Thread t2 = new Thread(() -> { Probe.enter(lockBA); Probe.lock(lockB); Probe.lock(lockA); Probe.unlock(lockA);
            Probe.unlock(lockB); Probe.exit(lockBA); }, "t2");
        t1.start();
        t1.join();
        t2.start();
        t2.join();
        // NumberFormatException caught by badSwallowed
        int swallowed = id("badSwallowed", "()V");
        int parse = Symbols.method("java/lang/Integer", "parseInt", "(Ljava/lang/String;)I");
        Probe.enter(swallowed);
        Probe.enter(parse);
        Probe.exitThrow(new NumberFormatException(), parse);
        Probe.exit(swallowed);
        Recorder.stop();
        withRuntime = Model.of(statics, Model.loadRecordings(List.of(dir.resolve("out"))));
    }

    private static int id(String name, String desc) {
        return Symbols.method("d/Fixture", name, desc);
    }

    /** method name -> rules reported for it */
    private static Map<String, Set<String>> byMethod(List<Finding> findings) {
        return findings.stream().filter(f -> f.method() != null)
                .collect(Collectors.groupingBy(f -> f.method().name(), TreeMap::new,
                        Collectors.mapping(Finding::rule, Collectors.toSet())));
    }

    private static void assertConvention(Detector d, Map<String, String> expected) {
        Map<String, Set<String>> got = byMethod(d.run(staticOnly));
        expected.forEach((method, rule) -> assertEquals(Set.of(rule), got.get(method), method + " in " + got));
        got.keySet().stream().filter(m -> m.startsWith("ok"))
                .forEach(m -> assertFalse(true, "false positive on " + m + ": " + got.get(m)));
    }

    @Test
    void nulls() {
        assertConvention(new NullDereferenceDetector(), Map.of(
                "badDefiniteNull", NullDereferenceDetector.DEFINITE,
                "badCheckedNull", NullDereferenceDetector.CHECKED,
                "badPossibleNull", NullDereferenceDetector.POSSIBLE));
        Finding f = new NullDereferenceDetector().run(staticOnly).stream()
                .filter(x -> x.method().name().equals("badCheckedNull")).findFirst().orElseThrow();
        assertTrue(f.message().startsWith("'s' is compared with null"), f.message());
        assertEquals(1, f.related().size(), "points at the null check");
    }

    @Test
    void resources() {
        assertConvention(new ResourceLeakDetector(), Map.of(
                "badLeakOnEarlyReturn", ResourceLeakDetector.LEAK,
                "badFactoryLeak", ResourceLeakDetector.LEAK,
                "badNoFinally", ResourceLeakDetector.LEAK_ON_EXCEPTION,
                "badHeaderWriteLeaks", ResourceLeakDetector.LEAK_ON_EXCEPTION));
    }

    @Test
    void exceptions() {
        assertConvention(new ExceptionDetector(), Map.of(
                "badSwallowed", ExceptionDetector.SWALLOWED,
                "badBroadSwallowed", ExceptionDetector.SWALLOWED,
                "badInterruptIgnored", ExceptionDetector.INTERRUPT,
                "badCauseLost", ExceptionDetector.CAUSE_LOST));
        Finding broad = new ExceptionDetector().run(staticOnly).stream()
                .filter(f -> f.method().name().equals("badBroadSwallowed")).findFirst().orElseThrow();
        assertEquals(Finding.Severity.MEDIUM, broad.severity());
    }

    @Test
    void staticLocks() {
        List<Finding> findings = new LockOrderDetector().run(staticOnly);
        Map<String, Long> rules = findings.stream().collect(Collectors.groupingBy(Finding::rule, Collectors.counting()));
        assertEquals(Map.of(LockOrderDetector.STATIC, 1L, LockOrderDetector.SAME_TYPE, 1L), rules, findings.toString());
        Finding cycle = findings.stream().filter(f -> f.rule().equals(LockOrderDetector.STATIC)).findFirst().orElseThrow();
        assertTrue(cycle.message().contains("field d.Fixture.a") && cycle.message().contains("field d.Fixture.b"),
                cycle.message());
        assertEquals(2, cycle.related().size());
        assertTrue(cycle.related().stream().anyMatch(l -> l.note().contains("calls d.Fixture.lockAOnly()")),
                cycle.related().toString());
        assertEquals("badTransfer", findings.stream().filter(f -> f.rule().equals(LockOrderDetector.SAME_TYPE))
                .findFirst().orElseThrow().method().name());
    }

    @Test
    void recursionAndDeadCode() {
        List<Finding> rec = new RecursionDetector().run(staticOnly);
        assertEquals(1, rec.size(), rec.toString());
        assertEquals(RecursionDetector.CYCLE, rec.get(0).rule());

        Map<String, Set<String>> dead = byMethod(new DeadCodeDetector().run(staticOnly));
        assertEquals(Map.of("badUnusedPrivate", Set.of(DeadCodeDetector.UNUSED_PRIVATE)), dead);
    }

    @Test
    void runtimeEvidence() {
        List<Finding> all = Detectors.run(withRuntime, Detectors.all());

        Finding npe = all.stream().filter(f -> f.rule().equals(ExceptionDetector.UNEXPECTED)).findFirst().orElseThrow();
        assertEquals("badCheckedNull", npe.method().name());
        Finding checked = all.stream().filter(f -> f.rule().equals(NullDereferenceDetector.CHECKED)).findFirst()
                .orElseThrow();
        assertTrue(checked.confirmedAtRuntime());
        assertEquals(Finding.Confidence.HIGH, checked.confidence());

        Finding deep = all.stream().filter(f -> f.rule().equals(RecursionDetector.DEEP)).findFirst().orElseThrow();
        assertEquals(MethodId.of("d/Fixture", "isEven", "(I)Z"), deep.method());

        Finding lockOrder = all.stream().filter(f -> f.rule().equals(LockOrderDetector.RUNTIME)).findFirst()
                .orElseThrow();
        assertEquals(Finding.Confidence.HIGH, lockOrder.confidence(), "seen on two threads");
        assertEquals(2, lockOrder.evidence().size());
        assertTrue(lockOrder.evidence().get(0).startsWith("thread 't"), lockOrder.evidence().toString());

        Finding swallowed = all.stream().filter(f -> f.rule().equals(ExceptionDetector.SWALLOWED)
                && f.method().name().equals("badSwallowed")).findFirst().orElseThrow();
        assertTrue(swallowed.confirmedAtRuntime(), swallowed.toString());

        // confirmed findings rank first
        int firstUnconfirmed = all.indexOf(all.stream().filter(f -> !f.confirmedAtRuntime()).findFirst().orElseThrow());
        assertTrue(all.subList(firstUnconfirmed, all.size()).stream().noneMatch(Finding::confirmedAtRuntime));

        StringWriter text = new StringWriter();
        Detectors.writeText(all, text);
        assertTrue(text.toString().contains("(confirmed at runtime)"), text.toString());
    }
}
