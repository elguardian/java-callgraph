package io.github.elguardian.mcpsettools.stat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.elguardian.mcpsettools.core.CallGraph;
import io.github.elguardian.mcpsettools.core.CallKind;
import io.github.elguardian.mcpsettools.core.MethodId;
import io.github.elguardian.mcpsettools.testkit.Compiled;
import io.github.elguardian.mcpsettools.testkit.TestCompiler;

class StaticAnalyzerTest {

    private static final String RUNNER = "@FunctionalInterface public interface Runner { void run(); }";

    @TempDir
    Path tmp;

    private StaticResult analyze(Compiled c, Algorithm algorithm) throws Exception {
        return StaticAnalyzer.create().app(c.writeJar(tmp.resolve("app-" + System.nanoTime() + ".jar")))
                .algorithm(algorithm).run();
    }

    private static String text(StaticResult r) {
        StringWriter w = new StringWriter();
        LegacyTextWriter.write(r, w);
        return w.toString();
    }

    private static MethodId m(String owner, String name, String desc) {
        return MethodId.of(owner, name, desc);
    }

    // --- ported from the original lambda.feature / native.feature ---

    @Test
    void lambdaInMethod() throws Exception {
        StaticResult r = analyze(TestCompiler.create().source("Runner", RUNNER).source("LambdaTest", """
                public class LambdaTest {
                 public void methodA() {
                  Runner r = () -> methodB();
                  r.run();
                 }
                 public void methodB() {}
                }
                """).compile(), Algorithm.CHA);
        String out = text(r);
        assertTrue(out.contains("M:LambdaTest:methodA() (D)Runner:run(LambdaTest)"), out);
        assertTrue(out.contains("M:LambdaTest:lambda$methodA$0() (M)LambdaTest:methodB()"), out);
        assertTrue(out.contains("C:LambdaTest Runner"), out);

        CallGraph g = r.graph();
        MethodId lambda = m("LambdaTest", "lambda$methodA$0", "()V");
        assertEquals(Set.of(CallKind.DYNAMIC), g.edge(m("LambdaTest", "methodA", "()V"), lambda).kinds());
        assertNotNull(g.edge(lambda, m("LambdaTest", "methodB", "()V")));
    }

    @Test
    void nestedLambdasFormAChainFromTheEnclosingMethod() throws Exception {
        StaticResult r = analyze(TestCompiler.create().source("Runner", RUNNER).source("NestedLambdaTest", """
                public class NestedLambdaTest {
                 public void methodA() {
                  Runner r = () -> {
                   Runner r2 = () -> {
                    Runner r3 = () -> methodB();
                    r3.run();
                   };
                   r2.run();
                  };
                  r.run();
                 }
                 public void methodB() {}
                }
                """).compile(), Algorithm.CHA);
        // javac's numbering of nested lambdas changed between releases, so follow the edges
        CallGraph g = r.graph();
        MethodId current = m("NestedLambdaTest", "methodA", "()V");
        for (int depth = 0; depth < 3; depth++) {
            Set<MethodId> lambdas = g.outgoing(current).stream().filter(e -> e.kinds().contains(CallKind.DYNAMIC))
                    .map(e -> e.callee()).collect(Collectors.toSet());
            assertEquals(1, lambdas.size(), "depth " + depth + ": " + g.outgoing(current));
            current = lambdas.iterator().next();
            assertTrue(current.name().startsWith("lambda$methodA$"), current.toString());
        }
        assertNotNull(g.edge(current, m("NestedLambdaTest", "methodB", "()V")));
        assertTrue(text(r).contains("M:NestedLambdaTest:methodA() (D)Runner:run(NestedLambdaTest)"));
    }

    @Test
    void nativeMethodCall() throws Exception {
        StaticResult r = analyze(TestCompiler.create().source("NativeTest", """
                public class NativeTest {
                 public void methodA() { methodB(); }
                 public native void methodB();
                }
                """).compile(), Algorithm.CHA);
        assertTrue(text(r).contains("M:NativeTest:methodA() (M)NativeTest:methodB()"));
        assertNotNull(r.graph().edge(m("NativeTest", "methodA", "()V"), m("NativeTest", "methodB", "()V")));
    }

    // --- call resolution ---

    private static final String SHAPES = """
            package s;
            interface Shape { double area(); default String label() { return "shape"; } }
            abstract class Base implements Shape { public String label() { return "base"; } }
            class Circle extends Base { public double area() { return 3; } }
            class Square extends Base { public double area() { return 4; } public String label() { return "sq"; } }
            final class Tri implements Shape { public double area() { return 1; } }
            public class Main {
                static double total(Shape s) { return s.area() + s.label().length(); }
                public static void main(String[] a) { total(new Circle()); }
            }
            """;

    @Test
    void chaDispatchesToEveryConcreteSubtype() throws Exception {
        StaticResult r = analyze(TestCompiler.create().source("s.Main", SHAPES).compile(), Algorithm.CHA);
        MethodId total = m("s/Main", "total", "(Ls/Shape;)D");
        Set<MethodId> callees = r.graph().callees(total);
        assertTrue(callees.containsAll(Set.of(m("s/Circle", "area", "()D"), m("s/Square", "area", "()D"),
                m("s/Tri", "area", "()D"))), callees.toString());
        // label(): Base overrides the default, Square overrides Base, Tri inherits the default
        assertTrue(callees.containsAll(Set.of(m("s/Base", "label", "()Ljava/lang/String;"),
                m("s/Square", "label", "()Ljava/lang/String;"), m("s/Shape", "label", "()Ljava/lang/String;"))),
                callees.toString());
        // library call resolved against the JDK
        assertTrue(callees.contains(m("java/lang/String", "length", "()I")), callees.toString());
        assertFalse(callees.contains(m("s/Shape", "area", "()D")), "abstract target must not be an edge");
    }

    @Test
    void rtaKeepsOnlyInstantiatedApplicationClasses() throws Exception {
        StaticResult r = analyze(TestCompiler.create().source("s.Main", SHAPES).compile(), Algorithm.RTA);
        Set<MethodId> callees = r.graph().callees(m("s/Main", "total", "(Ls/Shape;)D"));
        assertTrue(callees.contains(m("s/Circle", "area", "()D")));
        assertFalse(callees.contains(m("s/Square", "area", "()D")), callees.toString());
        assertFalse(callees.contains(m("s/Tri", "area", "()D")), callees.toString());
    }

    @Test
    void inheritedSuperAndStaticCallsResolveToTheDeclaringClass() throws Exception {
        StaticResult r = analyze(TestCompiler.create().source("h.C", """
                package h;
                class A { void foo() {} static void util() {} }
                class B extends A { void foo() { super.foo(); } }
                public class C extends B {
                    void run() { B.util(); new C().bar(); }
                    void bar() { foo(); }
                }
                """).compile(), Algorithm.CHA);
        CallGraph g = r.graph();
        assertNotNull(g.edge(m("h/B", "foo", "()V"), m("h/A", "foo", "()V")), "super call");
        assertNotNull(g.edge(m("h/C", "run", "()V"), m("h/A", "util", "()V")), "inherited static");
        assertNotNull(g.edge(m("h/C", "run", "()V"), m("h/C", "<init>", "()V")), "constructor");
        assertNotNull(g.edge(m("h/C", "bar", "()V"), m("h/B", "foo", "()V")), "inherited virtual");
    }

    @Test
    void methodReferencesLinkToTheirTarget() throws Exception {
        StaticResult r = analyze(TestCompiler.create().source("r.Refs", """
                package r;
                import java.util.function.*;
                public class Refs {
                    Function<String, Integer> len() { return String::length; }
                    Supplier<Refs> make() { return Refs::new; }
                    String concat(String a, int b) { return a + b; }
                }
                """).compile(), Algorithm.CHA);
        CallGraph g = r.graph();
        assertNotNull(g.edge(m("r/Refs", "len", "()Ljava/util/function/Function;"),
                m("java/lang/String", "length", "()I")));
        assertNotNull(g.edge(m("r/Refs", "make", "()Ljava/util/function/Supplier;"), m("r/Refs", "<init>", "()V")));
        // string concatenation is an invokedynamic call site, but not a graph edge
        assertTrue(r.callSites().stream().anyMatch(s -> s.kind() == CallKind.DYNAMIC && s.targets().isEmpty()));
    }

    @Test
    void readsClassDirectoriesAndJarsNestedInWars() throws Exception {
        Compiled c = TestCompiler.create().source("w.Servlet", "package w; public class Servlet { void s() { new Lib().l(); } }")
                .source("w.Lib", "package w; public class Lib { void l() {} }").compile();
        ByteArrayOutputStream libJar = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(libJar)) {
            z.putNextEntry(new ZipEntry("w/Lib.class"));
            z.write(c.bytes("w.Lib"));
        }
        Path war = tmp.resolve("app.war");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(war))) {
            z.putNextEntry(new ZipEntry("WEB-INF/classes/w/Servlet.class"));
            z.write(c.bytes("w.Servlet"));
            z.putNextEntry(new ZipEntry("WEB-INF/lib/lib.jar"));
            z.write(libJar.toByteArray());
        }
        StaticResult fromWar = StaticAnalyzer.create().app(war).run();
        assertNotNull(fromWar.graph().edge(m("w/Servlet", "s", "()V"), m("w/Lib", "l", "()V")));
        assertTrue(fromWar.appClasses().stream().anyMatch(k -> k.origin().endsWith("WEB-INF/lib/lib.jar!/w/Lib.class")));

        StaticResult fromDir = StaticAnalyzer.create().app(c.writeDir(tmp.resolve("classes"))).run();
        assertEquals(2, fromDir.appClasses().size());
    }

    @Test
    void libraryScopeIsUsedForResolutionOnly() throws Exception {
        Compiled lib = TestCompiler.create().source("l.Api", "package l; public interface Api { void go(); }")
                .source("l.Impl", "package l; public class Impl implements Api { public void go() {} }").compile();
        Path libJar = lib.writeJar(tmp.resolve("lib.jar"));
        Compiled app = TestCompiler.create().classpath(libJar)
                .source("a.User", "package a; public class User { void u(l.Api api) { api.go(); } }").compile();
        StaticResult r = StaticAnalyzer.create().app(app.writeJar(tmp.resolve("app.jar"))).lib(libJar).run();
        assertEquals(1, r.appClasses().size());
        assertEquals(Set.of(m("l/Impl", "go", "()V")), r.graph().callees(m("a/User", "u", "(Ll/Api;)V")));
        assertEquals(ClassScope.LIB, r.hierarchy().get("l/Impl").scope());
    }

    @Test
    void missingInputsAreReportedTogetherBeforeAnalysis() {
        NoSuchFileException e = assertThrows(NoSuchFileException.class, () -> StaticAnalyzer.create()
                .app(tmp.resolve("nope1.jar")).lib(tmp.resolve("nope2.jar")).run());
        assertTrue(e.getMessage().contains("nope1.jar") && e.getMessage().contains("nope2.jar"), e.getMessage());
    }

    @Test
    void unknownLibraryTypesKeepTheSymbolicReference() throws Exception {
        Compiled lib = TestCompiler.create().source("x.Ext", "package x; public class Ext { public void e() {} }").compile();
        Path libJar = lib.writeJar(tmp.resolve("ext.jar"));
        Compiled app = TestCompiler.create().classpath(libJar)
                .source("a.U", "package a; public class U { void u(x.Ext e) { e.e(); } }").compile();
        StaticResult r = StaticAnalyzer.create().app(app.writeJar(tmp.resolve("u.jar"))).run();
        assertEquals(Set.of(m("x/Ext", "e", "()V")), r.graph().callees(m("a/U", "u", "(Lx/Ext;)V")));
        assertTrue(r.hierarchy().missingClasses().contains("x/Ext"));
    }
}
