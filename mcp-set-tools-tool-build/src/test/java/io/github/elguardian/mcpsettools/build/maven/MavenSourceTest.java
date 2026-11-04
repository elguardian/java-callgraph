package io.github.elguardian.mcpsettools.build.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.github.elguardian.mcpsettools.timeline.Span;
import io.github.elguardian.mcpsettools.timeline.Timeline;
import io.github.elguardian.mcpsettools.timeline.Mark;

class MavenSourceTest {

    @Test
    void mavenModules() throws Exception {
        Path log = Path.of(MavenSourceTest.class.getResource("/maven/fail-T.log").toURI());
        Timeline t = MavenSource.read(MavenLogParser.parse(log));
        assertEquals(List.of("root@mvn-builder-root", "a@mvn-builder-a", "b@mvn-builder-b"),
                t.spans().stream().map(s -> s.label() + "@" + s.thread()).toList());
        assertEquals("failure", t.spans().get(2).category());
        assertTrue(t.notes().get(0).startsWith("1 module(s) have no timestamped lines"));
    }
}
