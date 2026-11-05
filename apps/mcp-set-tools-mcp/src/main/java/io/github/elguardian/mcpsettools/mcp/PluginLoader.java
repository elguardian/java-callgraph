package io.github.elguardian.mcpsettools.mcp;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import io.github.elguardian.mcpsettools.tool.ToolContext;

/**
 * Drop-in tool jars: every {@code *.jar} in the plugins directory joins a class loader below the
 * server's, so their {@code ToolProvider} and {@code TimelineSource} entries are found with the
 * bundled ones. The directory is {@code -Dmcpsettools.plugins}, {@code $MCPSETTOOLS_PLUGINS}, or
 * {@code <home>/plugins}.
 */
final class PluginLoader {

    private PluginLoader() {
    }

    static Path directory(ToolContext context) {
        String dir = System.getProperty("mcpsettools.plugins");
        if (dir == null || dir.isBlank()) {
            dir = context.secret("MCPSETTOOLS_PLUGINS");
        }
        return dir != null && !dir.isBlank() ? Path.of(dir) : context.home().resolve("plugins");
    }

    /** The jars in {@code dir}, sorted by name; none when it does not exist. */
    static List<Path> jars(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".jar") && Files.isRegularFile(p)).sorted().toList();
        }
    }

    /** A loader over the plugin jars, or {@code parent} itself when there are none. */
    static ClassLoader load(Path dir, ClassLoader parent, List<String> warnings) {
        List<Path> jars;
        try {
            jars = jars(dir);
        } catch (IOException e) {
            warnings.add("cannot list plugins in " + dir + ": " + e);
            return parent;
        }
        List<URL> urls = new ArrayList<>();
        for (Path jar : jars) {
            try {
                urls.add(jar.toUri().toURL());
            } catch (MalformedURLException e) {
                warnings.add("plugin " + jar + " skipped: " + e);
            }
        }
        return urls.isEmpty() ? parent : new URLClassLoader("mcp-set-tools-plugins", urls.toArray(URL[]::new), parent);
    }
}
