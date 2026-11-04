package gr.gousiosg.javacg.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Typed access to a tool call's arguments; invalid arguments raise a {@link ToolException}. */
final class ToolArgs {

    private final Map<String, Object> values;

    ToolArgs(Map<String, Object> values) {
        this.values = values == null ? Map.of() : values;
    }

    boolean has(String name) {
        Object v = values.get(name);
        return v != null && !(v instanceof String s && s.isBlank());
    }

    String require(String name) {
        if (!has(name)) {
            throw new ToolException("Missing argument '" + name + "'");
        }
        return string(name, null);
    }

    String string(String name, String dflt) {
        Object v = values.get(name);
        if (v == null || v instanceof String s && s.isBlank()) {
            return dflt;
        }
        return v.toString().strip();
    }

    /** A string array; a single string is accepted as a one-element array. */
    List<String> strings(String name) {
        Object v = values.get(name);
        List<String> result = new ArrayList<>();
        if (v instanceof Collection<?> c) {
            c.stream().filter(e -> e != null && !e.toString().isBlank()).forEach(e -> result.add(e.toString().strip()));
        } else if (v != null && !v.toString().isBlank()) {
            result.add(v.toString().strip());
        }
        return result;
    }

    List<Path> paths(String name) {
        return strings(name).stream().map(ToolArgs::path).toList();
    }

    int integer(String name, int dflt) {
        Object v = values.get(name);
        if (v == null) {
            return dflt;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString().strip());
        } catch (NumberFormatException e) {
            throw new ToolException("'" + name + "' must be an integer, got " + v);
        }
    }

    boolean bool(String name, boolean dflt) {
        Object v = values.get(name);
        if (v == null) {
            return dflt;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return switch (v.toString().strip().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new ToolException("'" + name + "' must be true or false, got " + v);
        };
    }

    <E extends Enum<E>> E choice(String name, Class<E> type, E dflt) {
        String v = string(name, null);
        if (v == null) {
            if (dflt == null) {
                throw new ToolException("Missing argument '" + name + "'");
            }
            return dflt;
        }
        try {
            return Enum.valueOf(type, v.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            List<String> allowed = new ArrayList<>();
            for (E c : type.getEnumConstants()) {
                allowed.add(c.name().toLowerCase(Locale.ROOT));
            }
            throw new ToolException("'" + name + "' must be one of " + allowed + ", got " + v);
        }
    }

    /** Absolute path; {@code ~/} is the user's home, relative paths are against the server's directory. */
    static Path path(String raw) {
        String p = raw.equals("~") || raw.startsWith("~/") ? System.getProperty("user.home") + raw.substring(1) : raw;
        return Path.of(p).toAbsolutePath().normalize();
    }
}
