package gr.gousiosg.javacg.stat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

/**
 * Reads classes from jars, class directories and deployment archives. WAR/EAR/RAR archives and
 * jars nested in them ({@code WEB-INF/lib}, EAR modules) are read recursively. Multi-release
 * variants under {@code META-INF/versions/} and {@code module-info} are skipped.
 */
final class ClassPathReader {

    private static final int CONSTANT_CLASS = 7;

    private final List<String> warnings;

    ClassPathReader(List<String> warnings) {
        this.warnings = warnings;
    }

    void read(Path input, ClassScope scope, Consumer<ClassInfo> sink) throws IOException {
        if (Files.isDirectory(input)) {
            try (Stream<Path> files = Files.walk(input)) {
                for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    String rel = input.relativize(f).toString().replace('\\', '/');
                    if (rel.endsWith(".class")) {
                        if (!rel.startsWith("META-INF/versions/")) {
                            parse(Files.readAllBytes(f), scope, f.toString(), sink);
                        }
                    } else if (isArchive(rel)) {
                        try (InputStream in = Files.newInputStream(f)) {
                            readArchive(in, scope, f.toString(), sink);
                        }
                    }
                }
            }
        } else if (input.toString().endsWith(".class")) {
            parse(Files.readAllBytes(input), scope, input.toString(), sink);
        } else {
            try (InputStream in = Files.newInputStream(input)) {
                readArchive(in, scope, input.toString(), sink);
            }
        }
    }

    private void readArchive(InputStream raw, ClassScope scope, String origin, Consumer<ClassInfo> sink)
            throws IOException {
        ZipInputStream zip = new ZipInputStream(raw);
        ZipEntry e;
        while ((e = zip.getNextEntry()) != null) {
            String name = e.getName();
            if (e.isDirectory() || name.startsWith("META-INF/versions/")) {
                continue;
            }
            if (name.endsWith(".class")) {
                parse(zip.readAllBytes(), scope, origin + "!/" + name, sink);
            } else if (isArchive(name)) {
                readArchive(new ByteArrayInputStream(zip.readAllBytes()), scope, origin + "!/" + name, sink);
            }
        }
    }

    private void parse(byte[] bytes, ClassScope scope, String origin, Consumer<ClassInfo> sink) {
        ClassReader cr;
        try {
            cr = new ClassReader(bytes);
        } catch (RuntimeException ex) {
            warnings.add("Cannot parse " + origin + ": " + ex);
            return;
        }
        if ((cr.getAccess() & Opcodes.ACC_MODULE) != 0) {
            return;
        }
        ClassNode cn = new ClassNode();
        try {
            if (scope == ClassScope.APP) {
                cr.accept(cn, ClassReader.SKIP_FRAMES);
                sink.accept(new ClassInfo(cn, scope, origin, constantPoolClasses(cr)));
            } else {
                cr.accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                sink.accept(new ClassInfo(cn, scope, origin, null));
            }
        } catch (RuntimeException ex) {
            warnings.add("Cannot parse " + origin + ": " + ex);
        }
    }

    /** Loads a class header from the running JDK, or returns null. */
    static ClassInfo fromJdk(String internalName) {
        try (InputStream in = ClassLoader.getPlatformClassLoader().getResourceAsStream(internalName + ".class")) {
            if (in == null) {
                return null;
            }
            ClassNode cn = new ClassNode();
            new ClassReader(in).accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return new ClassInfo(cn, ClassScope.JDK, "jrt:/" + internalName, null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Set<String> constantPoolClasses(ClassReader cr) {
        Set<String> names = new HashSet<>();
        char[] buf = new char[cr.getMaxStringLength()];
        for (int i = 1; i < cr.getItemCount(); i++) {
            int offset = cr.getItem(i);
            if (offset > 0 && cr.readByte(offset - 1) == CONSTANT_CLASS) {
                Object c = cr.readConst(i, buf);
                if (c instanceof Type t) {
                    names.add(t.getSort() == Type.OBJECT ? t.getClassName() : t.getDescriptor().replace('/', '.'));
                }
            }
        }
        return names;
    }

    private static boolean isArchive(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jar") || n.endsWith(".war") || n.endsWith(".ear") || n.endsWith(".rar")
                || n.endsWith(".sar");
    }

    static List<Path> missing(List<Path> inputs) {
        List<Path> missing = new ArrayList<>();
        for (Path p : inputs) {
            if (!Files.exists(p)) {
                missing.add(p);
            }
        }
        return missing;
    }
}
