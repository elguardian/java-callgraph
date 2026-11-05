package io.github.elguardian.mcpsettools.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.elguardian.mcpsettools.testkit.TestCompiler;

class StaticCommandTest {

    @TempDir
    Path tmp;

    record Run(int code, String out, String err) {
    }

    static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void writesAllFormats() throws Exception {
        Path jar = TestCompiler.create().source("p.A", "package p; public class A { void a() { b(); } void b() {} }")
                .compile().writeJar(tmp.resolve("a.jar"));

        Run text = run("static", jar.toString());
        assertEquals(0, text.code(), text.err());
        assertTrue(text.out().contains("M:p.A:a() (M)p.A:b()"), text.out());

        Path json = tmp.resolve("out/g.json");
        assertEquals(0, run("static", "--format", "json", "--output", json.toString(), "--app", jar.toString()).code());
        assertTrue(Files.readString(json).contains("\"caller\": \"p/A.a()V\""));

        Run dot = run("static", "--format=dot-classes", "--algorithm", "rta", jar.toString());
        assertTrue(dot.out().startsWith("digraph"), dot.out());
    }

    @Test
    void reportsUsageErrors() {
        assertEquals(1, run().code());
        assertEquals(1, run("bogus").code());
        Run r = run("static", "--format", "xml", "x.jar");
        assertEquals(1, r.code());
        assertTrue(r.err().contains("Invalid --format"), r.err());
        Run missing = run("static", tmp.resolve("missing.jar").toString());
        assertEquals(1, missing.code());
        assertTrue(missing.err().contains("missing.jar"), missing.err());
        assertEquals(0, run("help", "static").code());
    }
}
