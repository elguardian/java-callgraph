package io.github.elguardian.mcpsettools.cli;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command line arguments: {@code --name value}, {@code --name=value}, boolean {@code --flag}s and
 * positional arguments. Options may repeat.
 */
final class Args {

    static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(String msg) {
            super(msg);
        }
    }

    private final Map<String, List<String>> options = new LinkedHashMap<>();
    private final List<String> positional = new ArrayList<>();

    /**
     * @param flags   option names that take no value
     * @param valued  option names that take a value
     */
    static Args parse(List<String> argv, Set<String> flags, Set<String> valued) {
        Args a = new Args();
        for (int i = 0; i < argv.size(); i++) {
            String s = argv.get(i);
            if (!s.startsWith("--") || s.equals("--")) {
                a.positional.add(s);
                continue;
            }
            String name = s.substring(2);
            String value = null;
            int eq = name.indexOf('=');
            if (eq >= 0) {
                value = name.substring(eq + 1);
                name = name.substring(0, eq);
            }
            if (flags.contains(name)) {
                if (value != null) {
                    throw new UsageException("--" + name + " takes no value");
                }
                value = "true";
            } else if (valued.contains(name)) {
                if (value == null) {
                    if (i + 1 >= argv.size()) {
                        throw new UsageException("--" + name + " needs a value");
                    }
                    value = argv.get(++i);
                }
            } else {
                throw new UsageException("Unknown option --" + name);
            }
            a.options.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return a;
    }

    boolean flag(String name) {
        return options.containsKey(name);
    }

    String get(String name, String dflt) {
        List<String> v = options.get(name);
        return v == null ? dflt : v.get(v.size() - 1);
    }

    String require(String name) {
        String v = get(name, null);
        if (v == null) {
            throw new UsageException("Missing --" + name);
        }
        return v;
    }

    List<String> all(String name) {
        return options.getOrDefault(name, List.of());
    }

    /** All values of a path option; each value may itself be a path-separator separated list. */
    List<Path> paths(String name) {
        List<Path> result = new ArrayList<>();
        for (String v : all(name)) {
            for (String p : v.split(File.pathSeparator)) {
                if (!p.isEmpty()) {
                    result.add(Path.of(p));
                }
            }
        }
        return result;
    }

    List<String> positional() {
        return positional;
    }

    <E extends Enum<E>> E choice(String name, Class<E> type, E dflt) {
        String v = get(name, null);
        if (v == null) {
            return dflt;
        }
        String normalized = v.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(normalized)) {
                return e;
            }
        }
        throw new UsageException("Invalid --" + name + " " + v);
    }
}
