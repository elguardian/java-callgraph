package io.github.elguardian.mcpsettools.build.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenRunnerTest {

    @Test
    void followsModuleHeadersAsTheLogGrows(@TempDir Path tmp) throws Exception {
        Path log = tmp.resolve("build.log");
        Files.writeString(log, "");
        List<String> got = new ArrayList<>();
        MavenRunner.LogFollower f = new MavenRunner.LogFollower(log, (i, n, name) -> got.add(i + "/" + n + " " + name));

        Files.writeString(log, """
                [INFO] Reactor Build Order:
                [INFO] --------------------< io.example:core >--------------------
                [INFO] Building core 1.0-SNAPSHOT                                 [1/3]
                [INFO] Building jar: /p/core/target/core.jar
                12:00:01.123 [main] [INFO] Building web app 1.0-SNAPSHOT  [2/""", StandardOpenOption.APPEND);
        f.poll();
        assertEquals(List.of("1/3 core 1.0-SNAPSHOT"), got);

        // the rest of a line split across two polls
        Files.writeString(log, "3]\n[INFO] Building it 1.0-SNAPSHOT [3/3]\n", StandardOpenOption.APPEND);
        f.poll();
        f.poll();
        assertEquals(List.of("1/3 core 1.0-SNAPSHOT", "2/3 web app 1.0-SNAPSHOT", "3/3 it 1.0-SNAPSHOT"), got);
    }
}
