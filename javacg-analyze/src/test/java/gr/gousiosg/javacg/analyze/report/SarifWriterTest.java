package gr.gousiosg.javacg.analyze.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import gr.gousiosg.javacg.analyze.Model;
import gr.gousiosg.javacg.analyze.detect.Detectors;
import gr.gousiosg.javacg.analyze.detect.Finding;
import gr.gousiosg.javacg.core.MethodId;
import gr.gousiosg.javacg.stat.StaticAnalyzer;
import gr.gousiosg.javacg.testkit.TestCompiler;

class SarifWriterTest {

    @Test
    void writesSarifWithSourceLocations(@TempDir Path tmp) throws Exception {
        Path jar = TestCompiler.create().source("s.Bad", """
                package s;
                public class Bad {
                    int npe() {
                        String s = null;
                        return s.length();
                    }
                    static class Inner { void swallow() { try { Integer.parseInt("x"); } catch (RuntimeException e) { } } }
                }
                """).compile().writeJar(tmp.resolve("s.jar"));
        Model model = Model.of(StaticAnalyzer.create().app(jar).run(), List.of());
        List<Finding> findings = Detectors.run(model, Detectors.all());
        StringWriter w = new StringWriter();
        new SarifWriter(model, "src/main/java").write(findings, Detectors.rules(Detectors.all()), w);
        String sarif = w.toString();

        assertTrue(sarif.contains("\"version\": \"2.1.0\""), sarif);
        assertTrue(sarif.contains("\"name\": \"javacg\""), sarif);
        assertTrue(sarif.contains("\"ruleId\": \"null.dereference\""), sarif);
        assertTrue(sarif.contains("\"level\": \"error\""), sarif);
        assertTrue(sarif.contains("\"uri\": \"src/main/java/s/Bad.java\""), sarif);
        assertTrue(sarif.contains("\"startLine\": 5"), sarif);
        assertTrue(sarif.contains("\"fullyQualifiedName\": \"s.Bad$Inner.swallow()\""), sarif);
        assertTrue(sarif.contains("\"message\": {\n                \"text\": \"assigned null\""), sarif);
        assertEquals(count(sarif, '{'), count(sarif, '}'));
        assertEquals(count(sarif, '['), count(sarif, ']'));

        SarifWriter plain = new SarifWriter(model, "");
        assertEquals("s/Bad.java", plain.sourcePath(MethodId.of("s/Bad$Inner", "swallow", "()V")));
        assertTrue(!SarifWriter.version().equals("unknown"));
    }

    private static long count(String s, char c) {
        return s.chars().filter(x -> x == c).count();
    }
}
