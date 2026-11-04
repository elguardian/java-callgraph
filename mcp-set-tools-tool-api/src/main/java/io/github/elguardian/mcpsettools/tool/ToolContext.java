package io.github.elguardian.mcpsettools.tool;

import java.io.PrintStream;
import java.nio.file.Path;

/** What a host offers to tools: background jobs, a home directory for state, secrets, a log. */
public interface ToolContext {

    Jobs jobs();

    /** {@code ~/.mcp-set-tools} (or {@code $MCPSETTOOLS_HOME}): config files, stores and state. */
    Path home();

    /** An environment variable, else a system property of the same name; null when unset or blank. */
    String secret(String name);

    /** Diagnostics; never stdout, which may carry a protocol. */
    PrintStream log();

    static ToolContext standard() {
        return new ToolContext() {
            private final Jobs jobs = new Jobs();

            @Override
            public Jobs jobs() {
                return jobs;
            }

            @Override
            public Path home() {
                String h = secret("MCPSETTOOLS_HOME");
                return h != null ? Path.of(h) : Path.of(System.getProperty("user.home"), ".mcp-set-tools");
            }

            @Override
            public String secret(String name) {
                if (name == null) {
                    return null;
                }
                String v = System.getenv(name);
                if (v == null || v.isBlank()) {
                    v = System.getProperty(name);
                }
                return v == null || v.isBlank() ? null : v.strip();
            }

            @Override
            public PrintStream log() {
                return System.err;
            }
        };
    }
}
