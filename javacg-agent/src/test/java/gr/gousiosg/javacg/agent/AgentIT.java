package gr.gousiosg.javacg.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import gr.gousiosg.javacg.core.MethodId;
import gr.gousiosg.javacg.core.trace.CallStacks;
import gr.gousiosg.javacg.core.trace.Recording;
import gr.gousiosg.javacg.core.trace.TraceListener;
import gr.gousiosg.javacg.core.trace.TraceSummary;
import gr.gousiosg.javacg.core.trace.TraceText;
import gr.gousiosg.javacg.testkit.ForkedJvm;
import gr.gousiosg.javacg.testkit.TestCompiler;

/** Runs fixture programs under the packaged agent jar in forked JVMs. */
class AgentIT {

    private static final String APP = """
            package fx;

            import java.lang.management.ManagementFactory;
            import java.nio.file.*;
            import java.util.concurrent.locks.ReentrantLock;
            import javax.management.ObjectName;

            public class App {
                public static void main(String[] args) throws Exception {
                    switch (args[0]) {
                        case "basic" -> { a(); System.out.println("done"); }
                        case "exceptions" -> exceptions();
                        case "ctor" -> { try { new Child(-1); } catch (IllegalArgumentException e) { } new Child(1); }
                        case "threads" -> threads();
                        case "args" -> show(new Noisy(), 42, 7L, "x");
                        case "locks" -> locks();
                        case "manual" -> manual();
                        case "jmx" -> jmx();
                        case "attach" -> attach(Path.of(args[1]));
                        case "many" -> { for (int i = 0; i < 100_000; i++) small(i); }
                        default -> throw new IllegalArgumentException(args[0]);
                    }
                }

                static void a() { b(); b(); }
                static void b() { c(); }
                static void c() { }

                static void exceptions() {
                    try { thrower(); } catch (IllegalStateException e) { }
                    ok();
                }
                static void thrower() { deeper(); }
                static void deeper() { throw new IllegalStateException("boom"); }
                static void ok() { }

                static void threads() throws InterruptedException {
                    Thread[] ts = new Thread[4];
                    for (int i = 0; i < ts.length; i++) {
                        int n = i;
                        ts[i] = new Thread(() -> { for (int k = 0; k < 1000; k++) work(n); }, "worker-" + i);
                        ts[i].start();
                    }
                    for (Thread t : ts) t.join();
                }
                static int work(int n) { return n * 2; }

                static void show(Noisy n, int i, long l, String s) { }

                static final Object LOCK_A = new Object();
                static final ReentrantLock LOCK_B = new ReentrantLock();
                static void locks() {
                    synchronized (LOCK_A) {
                        LOCK_B.lock();
                        try { syncMethod(); } finally { LOCK_B.unlock(); }
                    }
                }
                static synchronized void syncMethod() { }

                static void manual() throws Exception {
                    Class<?> rec = Class.forName("gr.gousiosg.javacg.rt.Recorder");
                    a();
                    rec.getMethod("start", String.class).invoke(null, "first");
                    b();
                    rec.getMethod("stop").invoke(null);
                    c();
                    rec.getMethod("start", String.class).invoke(null, "second");
                    ok();
                    rec.getMethod("stop").invoke(null);
                }

                static void jmx() throws Exception {
                    var server = ManagementFactory.getPlatformMBeanServer();
                    ObjectName name = new ObjectName("javacg:type=Recorder");
                    for (int i = 0; i < 100 && !server.isRegistered(name); i++) Thread.sleep(50);
                    server.invoke(name, "start", new Object[] {"via-jmx"}, new String[] {"java.lang.String"});
                    ok();
                    System.out.println("dir=" + server.getAttribute(name, "RecordingDirectory"));
                    server.invoke(name, "stop", new Object[0], new String[0]);
                }

                static void attach(Path dir) throws Exception {
                    Files.writeString(dir.resolve("ready"), "");
                    while (!Files.exists(dir.resolve("go"))) { tick(); Thread.sleep(10); }
                    for (int i = 0; i < 10; i++) tick();
                }
                static void tick() { }

                static int small(int i) { return i + 1; }
            }

            class Noisy {
                @Override public String toString() {
                    System.out.println("TOSTRING CALLED");
                    return "noisy\\nvalue=1";
                }
            }

            class Parent { Parent(int x) { } }
            class Child extends Parent {
                Child(int x) {
                    super(x);
                    if (x < 0) throw new IllegalArgumentException();
                }
            }
            """;

    private static Path agentJar;
    private static Path appJar;

    @TempDir
    Path tmp;

    @BeforeAll
    static void build(@TempDir Path shared) throws Exception {
        agentJar = Path.of(System.getProperty("javacg.agent.jar", "target/javacg-agent.jar"));
        assertTrue(Files.isRegularFile(agentJar), "agent jar not built: " + agentJar);
        appJar = TestCompiler.create().source("fx.App", APP).compile().writeJar(shared.resolve("app.jar"));
    }

    private ForkedJvm.Result run(String agentArgs, String... appArgs) throws Exception {
        return ForkedJvm.create().classpath(appJar)
                .jvmArg("-javaagent:" + agentJar + "=" + agentArgs)
                .mainClass("fx.App").arg(appArgs).timeout(Duration.ofMinutes(2)).run().assertSuccess();
    }

    private String opts(String extra) {
        return "incl=fx\\..*;out=" + tmp + ";jmx=off" + (extra.isEmpty() ? "" : ";" + extra);
    }

    private Recording recording(String label) throws Exception {
        return Recording.open(tmp.resolve(label));
    }

    private static MethodId m(String name, String desc) {
        return MethodId.of("fx/App", name, desc);
    }

    @Test
    void recordsNestedCallsWithDepthAndCounts() throws Exception {
        ForkedJvm.Result r = run(opts(""), "basic");
        assertEquals("done", r.stdout().strip());
        Recording rec = recording("default");
        assertTrue(rec.isComplete());
        TraceSummary s = TraceSummary.of(rec);
        assertEquals(1, s.graph().edge(m("main", "([Ljava/lang/String;)V"), m("a", "()V")).runtimeCount());
        assertEquals(2, s.graph().edge(m("a", "()V"), m("b", "()V")).runtimeCount());
        assertEquals(2, s.graph().edge(m("b", "()V"), m("c", "()V")).runtimeCount());
        assertEquals(0, s.unwoundFrames());
        assertEquals(4, s.maxDepth());
        // instrumented but never executed methods are listed too
        assertTrue(rec.instrumentedMethods().contains(m("deeper", "()V")));
        assertTrue(rec.firstLine(rec.methodId(m("a", "()V"))) > 0);
    }

    @Test
    void exceptionalExitsKeepStacksBalanced() throws Exception {
        run(opts(""), "exceptions");
        Recording rec = recording("default");
        TraceSummary s = TraceSummary.of(rec);
        assertEquals(0, s.unwoundFrames());
        assertEquals(0, s.unmatchedExits());
        assertEquals(1L, s.stats().get(m("deeper", "()V")).exceptions().get("java.lang.IllegalStateException"));
        assertEquals(1L, s.stats().get(m("thrower", "()V")).exceptions().get("java.lang.IllegalStateException"));
        assertEquals(1L, s.stats().get(m("deeper", "()V")).thrownHere().get("java.lang.IllegalStateException"));
        assertTrue(s.stats().get(m("thrower", "()V")).thrownHere().isEmpty(), "propagated, not thrown");
        assertEquals(1L, s.stats().get(m("exceptions", "()V")).caught().get("java.lang.IllegalStateException"));
        assertNotNull(s.graph().edge(m("exceptions", "()V"), m("ok", "()V")));

        StringWriter text = new StringWriter();
        TraceText.write(rec, text);
        assertTrue(text.toString().contains("fx.App:deeper!java.lang.IllegalStateException="), text.toString());
        assertTrue(text.toString().lines().anyMatch(l -> l.matches(">\\[2]\\[\\d+]fx\\.App:ok=\\d+")), text.toString());
    }

    @Test
    void constructorsAreTracedIncludingExceptionsAfterSuper() throws Exception {
        run(opts(""), "ctor");
        TraceSummary s = TraceSummary.of(recording("default"));
        MethodId child = MethodId.of("fx/Child", "<init>", "(I)V");
        MethodId parent = MethodId.of("fx/Parent", "<init>", "(I)V");
        assertEquals(2, s.stats().get(child).calls());
        assertEquals(1L, s.stats().get(child).exceptions().get("java.lang.IllegalArgumentException"));
        assertEquals(2, s.graph().edge(child, parent).runtimeCount(), "super() runs inside the child frame");
        assertEquals(0, s.unwoundFrames());
    }

    @Test
    void threadsHaveSeparateStacks() throws Exception {
        run(opts(""), "threads");
        Recording rec = recording("default");
        TraceSummary s = TraceSummary.of(rec);
        assertEquals(4000, s.stats().get(m("work", "(I)I")).calls());
        assertEquals(0, s.unwoundFrames());
        Map<Long, Integer> perThread = new HashMap<>();
        int work = rec.methodId(m("work", "(I)I"));
        rec.replay(new TraceListener() {
            @Override
            public void enter(long thread, int method, long nanos) {
                if (method == work) {
                    perThread.merge(thread, 1, Integer::sum);
                }
            }
        });
        assertEquals(4, perThread.size());
        perThread.values().forEach(n -> assertEquals(1000, n));
        assertTrue(rec.threads().values().containsAll(List.of("worker-0", "worker-3")), rec.threads().toString());
    }

    @Test
    void argumentsAreNotTouchedByDefault() throws Exception {
        ForkedJvm.Result r = run(opts(""), "args");
        assertFalse(r.stdout().contains("TOSTRING CALLED"), "toString() must not run without args=tostring");
    }

    @Test
    void argumentCaptureEscapesValues() throws Exception {
        ForkedJvm.Result r = run(opts("args=tostring"), "args");
        assertTrue(r.stdout().contains("TOSTRING CALLED"));
        StringWriter text = new StringWriter();
        TraceText.write(recording("default"), text);
        assertTrue(text.toString().contains("fx.App:show [noisy\\nvalue\\u003d1, 42, 7, x]="), text.toString());
        assertTrue(text.toString().lines().allMatch(l -> l.matches("[<>]\\[\\d+]\\[\\d+][^=]+=\\d+")));

        run(opts("args=types;label=types"), "args");
        StringWriter types = new StringWriter();
        TraceText.write(recording("types"), types);
        assertTrue(types.toString().contains("[fx.Noisy, java.lang.Integer, java.lang.Long, java.lang.String]"),
                types.toString());
    }

    @Test
    void lockEventsCoverMonitorsSynchronizedMethodsAndLocks() throws Exception {
        run(opts("locks=true"), "locks");
        Recording rec = recording("default");
        List<String> events = new ArrayList<>();
        rec.replay(new CallStacks(new CallStacks.Listener() {
            @Override
            public void onLock(long thread, List<CallStacks.Frame> stack, int identityHash, int lockClass, long nanos,
                    boolean acquire) {
                String in = rec.method(stack.get(stack.size() - 1).method()).name();
                events.add((acquire ? "+" : "-") + rec.className(lockClass) + "@" + in);
            }
        }));
        assertEquals(List.of("+java.lang.Object@locks", "+java.util.concurrent.locks.ReentrantLock@locks",
                "+java.lang.Class@syncMethod", "-java.lang.Class@syncMethod",
                "-java.util.concurrent.locks.ReentrantLock@locks", "-java.lang.Object@locks"),
                events.subList(0, 6));
    }

    @Test
    void manualRecordingsAreScopedToStartAndStop() throws Exception {
        run(opts("record=manual"), "manual");
        assertFalse(Files.exists(tmp.resolve("default")));
        TraceSummary first = TraceSummary.of(recording("first"));
        TraceSummary second = TraceSummary.of(recording("second"));
        assertTrue(first.stats().containsKey(m("b", "()V")));
        assertTrue(first.stats().containsKey(m("c", "()V")));
        assertFalse(first.stats().containsKey(m("a", "()V")));
        assertEquals(1, second.stats().size(), second.stats().toString());
        assertTrue(second.stats().containsKey(m("ok", "()V")));
    }

    @Test
    void recorderIsControllableOverJmx() throws Exception {
        ForkedJvm.Result r = run("incl=fx\\..*;out=" + tmp + ";record=manual;jmx=0", "jmx");
        assertTrue(r.stdout().contains("dir=" + tmp.resolve("via-jmx")), r.stdout());
        assertTrue(TraceSummary.of(recording("via-jmx")).stats().containsKey(m("ok", "()V")));
    }

    @Test
    void attachesToARunningJvmAndRetransformsLoadedClasses() throws Exception {
        Path work = Files.createDirectories(tmp.resolve("work"));
        Path out = tmp.resolve("out");
        ProcessBuilder pb = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:+EnableDynamicAgentLoading", "-cp", appJar.toString(), "fx.App", "attach", work.toString())
                .redirectErrorStream(true).redirectOutput(tmp.resolve("attach.log").toFile());
        Process p = pb.start();
        try {
            for (int i = 0; i < 500 && !Files.exists(work.resolve("ready")); i++) {
                Thread.sleep(20);
            }
            assertTrue(Files.exists(work.resolve("ready")), "fixture did not start");
            com.sun.tools.attach.VirtualMachine vm = com.sun.tools.attach.VirtualMachine.attach(Long.toString(p.pid()));
            try {
                vm.loadAgent(agentJar.toString(), "incl=fx\\..*;out=" + out + ";jmx=off");
            } finally {
                vm.detach();
            }
            Files.writeString(work.resolve("go"), "");
            assertEquals(0, p.waitFor(), Files.readString(tmp.resolve("attach.log")));
        } finally {
            p.destroyForcibly();
        }
        TraceSummary s = TraceSummary.of(Recording.open(out.resolve("default")));
        assertTrue(s.stats().get(m("tick", "()V")).calls() >= 10, s.stats().toString());
    }

    @Test
    void bufferFlushesLoseNothing() throws Exception {
        run(opts(""), "many");
        assertEquals(100_000, TraceSummary.of(recording("default")).stats().get(m("small", "(I)I")).calls());
    }

    @Test
    void anUnwritableOutputDirectoryDoesNotBreakTheApplication() throws Exception {
        Path blocker = Files.writeString(tmp.resolve("not-a-dir"), "");
        ForkedJvm.Result r = run("incl=fx\\..*,[oops;jmx=off;out=" + blocker, "basic");
        assertEquals("done", r.stdout().strip());
        assertTrue(r.stderr().contains("ignoring invalid pattern"), r.stderr());
        assertTrue(r.stderr().contains("cannot start recording"), r.stderr());
    }
}
