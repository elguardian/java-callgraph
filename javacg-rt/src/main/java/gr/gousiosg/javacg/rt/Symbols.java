package gr.gousiosg.javacg.rt;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-wide table of method and class name ids. Methods are registered by the agent when a
 * class is instrumented, so the table also lists instrumented methods that never ran.
 */
public final class Symbols {

    private static final Map<String, Integer> IDS = new ConcurrentHashMap<>();
    private static final Map<Integer, String> ENTRIES = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> LINES = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT = new AtomicInteger(1);

    private static final ClassValue<Integer> CLASS_IDS = new ClassValue<>() {
        @Override
        protected Integer computeValue(Class<?> type) {
            return className(type.getName());
        }
    };

    private Symbols() {
    }

    /** Id of a method; {@code owner} is an internal name ({@code a/b/C}). */
    public static int method(String owner, String name, String descriptor) {
        return id("M\t" + owner + "\t" + name + "\t" + descriptor);
    }

    /** Records the first source line of a method, if known. */
    public static void line(int methodId, int line) {
        LINES.putIfAbsent(methodId, line);
    }

    /** Id of a class name ({@code a.b.C}). */
    public static int className(String name) {
        return id("C\t" + name);
    }

    static int classOf(Object o) {
        return CLASS_IDS.get(o.getClass());
    }

    private static int id(String key) {
        Integer id = IDS.get(key);
        if (id != null) {
            return id;
        }
        return IDS.computeIfAbsent(key, k -> {
            int n = NEXT.getAndIncrement();
            ENTRIES.put(n, k);
            return n;
        });
    }

    static void write(Path file) throws IOException {
        Map<Integer, String> sorted = new TreeMap<>(ENTRIES);
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (Map.Entry<Integer, String> e : sorted.entrySet()) {
                w.write(e.getKey() + "\t" + e.getValue());
                if (e.getValue().startsWith("M\t")) {
                    w.write("\t" + LINES.getOrDefault(e.getKey(), -1));
                }
                w.newLine();
            }
        }
    }
}
