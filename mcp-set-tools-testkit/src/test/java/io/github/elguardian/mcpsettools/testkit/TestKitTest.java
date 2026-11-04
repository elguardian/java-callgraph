package io.github.elguardian.mcpsettools.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestKitTest {

    @TempDir
    Path tmp;

    @Test
    void compilesNestedClassesAndRunsThemInAForkedJvm() throws Exception {
        Compiled c = TestCompiler.create()
                .source("p.Hello", """
                        package p;
                        public class Hello {
                            static class Inner { String msg() { return "hi"; } }
                            public static void main(String[] a) { System.out.println(new Inner().msg() + a[0]); }
                        }
                        """)
                .compile();
        assertTrue(c.classes().containsKey("p/Hello"));
        assertTrue(c.classes().containsKey("p/Hello$Inner"));

        Path jar = c.writeJar(tmp.resolve("hello.jar"));
        ForkedJvm.Result r = ForkedJvm.create().classpath(jar).mainClass("p.Hello").arg("!").run().assertSuccess();
        assertEquals("hi!", r.stdout().strip());
    }

    @Test
    void reportsCompilationErrors() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TestCompiler.create().source("Bad", "class Bad { int x = ; }").compile());
        assertTrue(e.getMessage().contains("Bad"), e.getMessage());
    }
}
