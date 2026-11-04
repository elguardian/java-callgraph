package io.github.elguardian.mcpsettools.testkit;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Compiles Java sources held in memory, for use as analysis fixtures.
 *
 * <pre>
 * Compiled c = TestCompiler.create()
 *         .source("p.Foo", "package p; public class Foo { }")
 *         .compile();
 * </pre>
 */
public final class TestCompiler {

    private final Map<String, String> sources = new LinkedHashMap<>();
    private final List<String> options = new ArrayList<>(List.of("-g", "-proc:none"));
    private final List<Path> classpath = new ArrayList<>();

    private TestCompiler() {
    }

    public static TestCompiler create() {
        return new TestCompiler();
    }

    /** Adds a compilation unit; {@code className} is the binary name of its top-level class. */
    public TestCompiler source(String className, String code) {
        sources.put(className, code);
        return this;
    }

    public TestCompiler option(String... opts) {
        options.addAll(List.of(opts));
        return this;
    }

    public TestCompiler classpath(Path... entries) {
        classpath.addAll(List.of(entries));
        return this;
    }

    public Compiled compile() {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler; run the tests on a JDK");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager standard = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
        Map<String, ByteArrayOutputStream> outputs = new TreeMap<>();
        JavaFileManager manager = new ForwardingJavaFileManager<>(standard) {
            @Override
            public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                    FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + kind.extension),
                        kind) {
                    @Override
                    public OutputStream openOutputStream() {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        outputs.put(className.replace('.', '/'), out);
                        return out;
                    }
                };
            }
        };

        List<String> opts = new ArrayList<>(options);
        if (!classpath.isEmpty()) {
            opts.add("-classpath");
            opts.add(classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
        }
        List<JavaFileObject> units = new ArrayList<>();
        sources.forEach((name, code) -> units.add(new StringSource(name, code)));

        boolean ok = compiler.getTask(null, manager, diagnostics, opts, null, units).call();
        if (!ok) {
            StringBuilder sb = new StringBuilder("Compilation failed:\n");
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                sb.append(d.getKind()).append(' ')
                        .append(d.getSource() == null ? "?" : d.getSource().getName())
                        .append(':').append(d.getLineNumber()).append(": ")
                        .append(d.getMessage(null)).append('\n');
            }
            throw new IllegalArgumentException(sb.toString());
        }
        try {
            standard.close();
        } catch (IOException e) {
            // nothing was written through the standard manager
        }
        Map<String, byte[]> classes = new TreeMap<>();
        outputs.forEach((name, out) -> classes.put(name, out.toByteArray()));
        return new Compiled(classes);
    }

    private static final class StringSource extends SimpleJavaFileObject {
        private final String code;

        StringSource(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
