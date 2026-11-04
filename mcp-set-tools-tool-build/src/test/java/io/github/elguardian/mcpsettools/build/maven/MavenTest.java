package io.github.elguardian.mcpsettools.build.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenTest {

    static Path log(String name) throws Exception {
        return Path.of(MavenTest.class.getResource("/maven/" + name).toURI());
    }

    @Test
    void reactorOfThisProject() throws Exception {
        Reactor r = Reactor.load(Path.of(".."), Set.of());
        // order constraints that hold however the modules are split (checked against 'mvn validate')
        List<String> order = r.buildOrder().stream().map(m -> m.gav().artifactId()).toList();
        assertEquals("mcp-set-tools-parent", order.get(0));
        for (String[] before : new String[][] { { "mcp-set-tools-rt", "mcp-set-tools-core" }, { "mcp-set-tools-core", "mcp-set-tools-static" },
            { "mcp-set-tools-static", "mcp-set-tools-analyze" }, { "mcp-set-tools-analyze", "mcp-set-tools-mcp" }, { "mcp-set-tools-agent", "mcp-set-tools-mcp" } }) {
            assertTrue(order.indexOf(before[0]) >= 0 && order.indexOf(before[0]) < order.indexOf(before[1]),
                    before[0] + " before " + before[1] + " in " + order);
        }
        Reactor.Module core = r.find("mcp-set-tools-core");
        assertEquals("2.0.0-SNAPSHOT", core.gav().version());
        assertTrue(core.dependsOn().containsAll(Set.of("io.github.elguardian:mcp-set-tools-parent", "io.github.elguardian:mcp-set-tools-rt")),
                core.dependsOn().toString());
        Reactor.Module owner = r.owner(Path.of("mcp-set-tools-core/src/main/java/io/github/elguardian/mcpsettools/core/Edge.java")).orElseThrow();
        assertEquals(core, owner);
        List<String> amd = r.select(List.of(core), false, true).stream().map(m -> m.gav().artifactId()).toList();
        assertEquals("mcp-set-tools-core", amd.get(0));
        assertTrue(amd.containsAll(List.of("mcp-set-tools-static", "mcp-set-tools-agent", "mcp-set-tools-analyze", "mcp-set-tools-cli", "mcp-set-tools-mcp")), amd.toString());
        assertTrue(!amd.contains("mcp-set-tools-rt") && !amd.contains("mcp-set-tools-testkit"), amd.toString());
        assertEquals("mcp-set-tools-core,mcp-set-tools-static", r.plArgument(List.of(core, r.find(":mcp-set-tools-static"))));
    }

    @Test
    void profilesPropertiesAndCycles(@TempDir Path tmp) throws Exception {
        pom(tmp, """
                <groupId>g</groupId><artifactId>root</artifactId><version>${revision}</version><packaging>pom</packaging>
                <properties><revision>3.1</revision></properties>
                <modules><module>a</module></modules>
                <profiles><profile><id>extra</id><modules><module>b</module></modules></profile></profiles>
                """);
        pom(tmp.resolve("a"), """
                <parent><groupId>g</groupId><artifactId>root</artifactId><version>${revision}</version></parent>
                <artifactId>a</artifactId>
                """);
        pom(tmp.resolve("b"), """
                <parent><groupId>g</groupId><artifactId>root</artifactId><version>${revision}</version></parent>
                <artifactId>b</artifactId>
                <dependencies><dependency><groupId>${project.groupId}</groupId><artifactId>a</artifactId>
                <version>${project.version}</version></dependency></dependencies>
                """);
        assertEquals(2, Reactor.load(tmp, Set.of()).modules().size());
        Reactor r = Reactor.load(tmp, Set.of("extra"));
        Reactor.Module b = r.find("b");
        assertEquals("3.1", b.gav().version());
        assertEquals(Set.of("g:root", "g:a"), b.dependsOn());
        assertEquals(List.of("root", "a", "b"), r.buildOrder().stream().map(m -> m.gav().artifactId()).toList());
        assertEquals(List.of("a", "b"), r.select(List.of(b), true, false).stream().skip(1)
                .map(m -> m.gav().artifactId()).toList());

        pom(tmp.resolve("a"), """
                <parent><groupId>g</groupId><artifactId>root</artifactId><version>${revision}</version></parent>
                <artifactId>a</artifactId>
                <dependencies><dependency><groupId>g</groupId><artifactId>b</artifactId></dependency></dependencies>
                """);
        IllegalStateException cycle = assertThrows(IllegalStateException.class,
                () -> Reactor.load(tmp, Set.of("extra")).buildOrder());
        assertTrue(cycle.getMessage().contains("g:a -> g:b -> g:a"), cycle.getMessage());
    }

    private static void pom(Path dir, String body) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion>" + body + "</project>");
    }

    @Test
    void testFailuresLog() throws Exception {
        MavenBuild b = MavenLogParser.parse(log("fail.log"));
        assertEquals(MavenBuild.Status.FAILURE, b.status());
        assertEquals(List.of("Fix Root:SUCCESS", "a:SUCCESS", "b:FAILURE", "c:SKIPPED"),
                b.modules().stream().map(m -> m.name() + ":" + m.status()).toList());
        MavenBuild.Module bm = b.modules().get(2);
        assertEquals("b", bm.artifactId());
        assertEquals(0.370, bm.seconds());
        assertTrue(bm.executions().contains("surefire:test (default-test)"), bm.executions().toString());
        assertEquals(List.of("b.BTest.npe:error", "b.BTest.five:failure"),
                b.testFailures().stream().map(f -> f.test() + ":" + f.kind()).toList());
        assertEquals("b", b.testFailures().get(0).module());
        assertTrue(b.testFailures().get(1).trace().startsWith("org.opentest4j.AssertionFailedError: math ==> expected"));
        assertTrue(b.testFailures().get(1).trace().endsWith("at b.BTest.five(BTest.java:6)"));
        assertEquals(List.of("failures: BTest.five:6 math ==> expected: <5> but was: <4>",
                "errors: BTest.npe:7 NullPointer Cannot invoke \"Object.toString()\" because \"o\" is null"), b.testSummary());
        assertEquals(List.of(new MavenBuild.TestCounts("b", "b.BTest", 3, 1, 1, 0), new MavenBuild.TestCounts("b", null, 3, 1, 1, 0)),
                b.testCounts());
        MavenBuild.GoalFailure g = b.goalFailures().get(0);
        assertEquals("org.apache.maven.plugins:maven-surefire-plugin:3.6.1:test", g.plugin());
        assertEquals("b", g.module());
        assertEquals("There are test failures.", g.message());
        assertEquals(":b", b.resumeFrom());
        assertEquals(0.758, b.totalSeconds());
        assertEquals(List.of("Fix Root [pom]", "a [jar]", "b [jar]", "c [jar]"), b.reactorOrder());
        assertTrue(b.errors().isEmpty(), b.errors().toString());
    }

    @Test
    void compileErrorLog() throws Exception {
        MavenBuild b = MavenLogParser.parse(log("compile.log"));
        assertEquals(1, b.compilerErrors().size());
        MavenBuild.CompilerMessage e = b.compilerErrors().get(0);
        assertEquals("/work/fix/c/src/main/java/c/C.java", e.file());
        assertEquals(1, e.line());
        assertEquals(50, e.column());
        assertEquals("c", e.module());
        assertEquals("cannot find symbol\nsymbol:   method missing()\nlocation: class c.C", e.message());
        assertEquals("Compilation failure", b.goalFailures().get(0).message());
    }

    @Test
    void parallelLogHasThreadsAndTimes() throws Exception {
        MavenBuild b = MavenLogParser.parse(log("fail-T.log"));
        MavenBuild.Module a = b.modules().get(1);
        assertEquals("mvn-builder-a", a.thread());
        assertTrue(a.start() > 0 && a.end() > a.start(), a.toString());
        assertEquals("mvn-builder-b", b.modules().get(2).thread());
        assertNull(b.modules().get(3).thread());
        assertEquals(List.of("b.BTest.npe", "b.BTest.five"), b.testFailures().stream().map(MavenBuild.TestFailure::test).toList());
    }

    @Test
    void durations() {
        assertEquals(65, MavenLogParser.seconds("01:05 min"));
        assertEquals(3720, MavenLogParser.seconds("01:02 h"));
        assertEquals(1.5, MavenLogParser.seconds("1.500 s"));
    }
}
