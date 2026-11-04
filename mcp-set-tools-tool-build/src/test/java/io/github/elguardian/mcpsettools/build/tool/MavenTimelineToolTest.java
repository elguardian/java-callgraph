package io.github.elguardian.mcpsettools.build.tool;

import io.github.elguardian.mcpsettools.testkit.ToolHarness;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.error;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.map;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.ok;
import static io.github.elguardian.mcpsettools.testkit.ToolHarness.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenTimelineToolTest {

    @TempDir
    Path tmp;

    ToolHarness tools = new ToolHarness();

    @Test
    void mavenBuild() throws Exception {
        Path log = Path.of(MavenTimelineToolTest.class.getResource("/maven-T.log").toURI());
        Map<String, Object> m = ok(tools, "timeline", "source", "maven", "log_file", log.toString(), "format", "plantuml");
        assertEquals(3, m.get("spans"));
        assertTrue(((String) m.get("diagram")).contains("concise \"mvn-builder-a\""), (String) m.get("diagram"));
        assertEquals(List.of("mvn-builder-root", "mvn-builder-a", "mvn-builder-b"),
                ((List<?>) m.get("lanes")).stream().map(x -> map(x).get("thread")).toList());
    }
}
