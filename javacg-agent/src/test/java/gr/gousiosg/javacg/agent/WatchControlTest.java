package gr.gousiosg.javacg.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import gr.gousiosg.javacg.rt.Watch;
import gr.gousiosg.javacg.testkit.Compiled;
import gr.gousiosg.javacg.testkit.TestCompiler;

/** Watch bytecode, loaded and run: the verifier checks it, the probes count calls. */
class WatchControlTest {

    static final String SOURCE = """
            package w;
            public class Target {
                private final int base;
                public Target(int base) { this.base = base; }
                public int add(int x, String label) { return base + x + label.length(); }
                public long big(long x) { return x * 2; }
                public void nothing() { }
                public int fail(int x) {
                    if (x > 0) { throw new IllegalStateException("x=" + x); }
                    return x;
                }
                public int recurse(int n) { return n <= 0 ? 0 : 1 + recurse(n - 1); }
            }
            """;

    static final class Loader extends ClassLoader {
        private final Map<String, byte[]> classes;

        Loader(Map<String, byte[]> classes) {
            super(WatchControlTest.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] b = classes.get(name);
            if (b == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, b, 0, b.length);
        }
    }

    private static Class<?> watched(Watch.State state, String method, String params) {
        Compiled c = TestCompiler.create().source("w.Target", SOURCE).compile();
        byte[] original = c.bytes("w.Target");
        byte[] instrumented = WatchControl.instrument(original, state, WatchControl.glob(method), params, true);
        try {
            return new Loader(Map.of("w.Target", instrumented == null ? original : instrumented)).loadClass("w.Target");
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void countsCallsResultsAndExceptions() throws Exception {
        Watch.State s = Watch.create("w.Target.*", Watch.Capture.TOSTRING, 10, 0, false);
        Class<?> cls = watched(s, "*", null);
        Object t = cls.getConstructor(int.class).newInstance(10);
        Method add = cls.getMethod("add", int.class, String.class);
        assertEquals(15, add.invoke(t, 2, "abc"));
        assertEquals(84L, cls.getMethod("big", long.class).invoke(t, 42L));
        cls.getMethod("nothing").invoke(t);
        assertEquals(3, cls.getMethod("recurse", int.class).invoke(t, 3));
        InvocationTargetException e = assertThrows(InvocationTargetException.class, () -> cls.getMethod("fail", int.class).invoke(t, 1));
        assertEquals("x=1", e.getCause().getMessage());
        s.stop();
        String json = Watch.json(s);
        // constructor + add + big + nothing + 4 recurse + fail
        assertTrue(json.contains("\"calls\":9"), json);
        assertTrue(json.contains("\"failed\":1"), json);
        assertTrue(json.contains("\"args\":[\"2\",\"abc\"],\"returned\":\"15\""), json);
        assertTrue(json.contains("\"returned\":\"84\""), json);
        assertTrue(json.contains("\"returned\":\"void\""), json);
        assertTrue(json.contains("\"type\":\"java.lang.IllegalStateException\",\"count\":1"), json);
        assertTrue(json.contains("\"w.Target.add(ILjava/lang/String;)I\""), json);
    }

    @Test
    void parameterFilters() {
        Watch.State s = Watch.create("w.Target.add(int,java.lang.String)", Watch.Capture.NONE, 5, 0, false);
        watched(s, "add", "(int,java.lang.String)");
        assertEquals(1, s.methodCount());
        Watch.State d = Watch.create("w.Target.big(J)", Watch.Capture.NONE, 5, 0, false);
        watched(d, "big", "(J)");
        assertEquals(1, d.methodCount());
        Watch.State none = Watch.create("w.Target.add(long)", Watch.Capture.NONE, 5, 0, false);
        watched(none, "add", "(long)");
        assertEquals(0, none.methodCount());
        assertTrue(WatchControl.glob("com.acme.*Dao").matcher("com.acme.sub.OrderDao").matches());
        assertFalse(WatchControl.glob("com.acme.*Dao").matcher("com.acme.OrderDaoImpl").matches());
        assertTrue(WatchControl.paramsMatch("()", "()V"));
        assertTrue(Pattern.matches(WatchControl.glob("*").pattern(), "anything"));
    }
}
