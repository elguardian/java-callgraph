package io.github.elguardian.mcpsettools.mcp;

import java.nio.file.Files;
import java.nio.file.Path;

import io.github.elguardian.mcpsettools.devtools.git.Git;

/** A git repository built by a test. */
final class TestRepo {

    final Path dir;

    TestRepo(Path dir) throws Exception {
        this.dir = dir;
        Files.createDirectories(dir);
        git("init", "-q", "-b", "main");
    }

    TestRepo write(String path, String content) throws Exception {
        Path f = dir.resolve(path);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
        return this;
    }

    String commit(String message) throws Exception {
        git("add", "-A");
        git("-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", message);
        return git("rev-parse", "HEAD").strip();
    }

    TestRepo tag(String tag) throws Exception {
        git("tag", tag);
        return this;
    }

    String git(String... args) throws Exception {
        return new Git(dir).run(args);
    }
}
