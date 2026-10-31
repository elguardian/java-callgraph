package gr.gousiosg.javacg.testkit;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Result of {@link TestCompiler#compile()}: class files keyed by internal name ({@code a/b/C$D}). */
public final class Compiled {

    private final Map<String, byte[]> classes;

    Compiled(Map<String, byte[]> classes) {
        this.classes = Collections.unmodifiableMap(classes);
    }

    public Map<String, byte[]> classes() {
        return classes;
    }

    /** Accepts a binary ({@code a.b.C}) or internal ({@code a/b/C}) name. */
    public byte[] bytes(String className) {
        byte[] b = classes.get(className.replace('.', '/'));
        if (b == null) {
            throw new IllegalArgumentException("No class " + className + " in " + classes.keySet());
        }
        return b;
    }

    public Path writeJar(Path jar) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        return writeJar(jar, manifest);
    }

    public Path writeJar(Path jar, Manifest manifest) throws IOException {
        if (jar.getParent() != null) {
            Files.createDirectories(jar.getParent());
        }
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file, manifest)) {
            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey() + ".class"));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return jar;
    }

    public Path writeDir(Path dir) throws IOException {
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            Path file = dir.resolve(e.getKey() + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, e.getValue());
        }
        return dir;
    }
}
