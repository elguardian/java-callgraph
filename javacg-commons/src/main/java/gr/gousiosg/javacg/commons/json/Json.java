package gr.gousiosg.javacg.commons.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON without dependencies: {@link #parse} gives maps (insertion ordered), lists, strings,
 * longs or doubles, booleans and null; {@link #write} the compact form of the same types.
 */
public final class Json {

    private Json() {
    }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object v = p.value();
        p.ws();
        if (p.i < text.length()) {
            throw p.error("trailing text");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text) {
        Object v = parse(text);
        if (!(v instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Not a JSON object");
        }
        return (Map<String, Object>) v;
    }

    /** Compact JSON of maps, collections, arrays of objects, strings, numbers, booleans, null; other values as strings. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            sb.append(v);
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            sb.append(Double.isFinite(d) ? (d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : v.toString())
                    : "null");
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                string(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> l) {
            sb.append('[');
            boolean first = true;
            for (Object o : l) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else if (v instanceof Object[] a) {
            write(sb, List.of(a));
        } else {
            string(sb, v.toString());
        }
    }

    public static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        Object value() {
            ws();
            if (i >= s.length()) {
                throw error("unexpected end");
            }
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            i++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                m.put(k, value());
                ws();
                char c = next();
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> array() {
            i++;
            List<Object> l = new ArrayList<>();
            ws();
            if (peek() == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(value());
                ws();
                char c = next();
                if (c == ']') {
                    return l;
                }
                if (c != ',') {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = next();
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw error("bad \\u escape");
                        }
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> sb.append(e);
                }
            }
        }

        private Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String t = s.substring(start, i);
            if (t.isEmpty()) {
                throw error("unexpected character '" + s.charAt(start) + "'");
            }
            try {
                return t.contains(".") || t.contains("e") || t.contains("E") ? (Object) Double.parseDouble(t) : (Object) Long.parseLong(t);
            } catch (NumberFormatException e) {
                return Double.parseDouble(t);
            }
        }

        private Object literal(String word, Object value) {
            if (!s.startsWith(word, i)) {
                throw error("unexpected token");
            }
            i += word.length();
            return value;
        }

        private char peek() {
            if (i >= s.length()) {
                throw error("unexpected end");
            }
            return s.charAt(i);
        }

        private char next() {
            char c = peek();
            i++;
            return c;
        }

        private void expect(char c) {
            if (next() != c) {
                i--;
                throw error("expected '" + c + "'");
            }
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        IllegalArgumentException error(String what) {
            return new IllegalArgumentException("Invalid JSON at " + i + ": " + what);
        }
    }

    // ---- navigation over parsed values

    /** The value at a path of object keys, or null. */
    public static Object at(Object root, String... path) {
        Object o = root;
        for (String k : path) {
            if (!(o instanceof Map<?, ?> m)) {
                return null;
            }
            o = m.get(k);
        }
        return o;
    }

    public static String str(Object root, String... path) {
        Object v = at(root, path);
        return v == null ? null : v.toString();
    }

    public static List<?> list(Object root, String... path) {
        return at(root, path) instanceof List<?> l ? l : List.of();
    }

    public static boolean bool(Object root, String... path) {
        return Boolean.TRUE.equals(at(root, path));
    }

    public static long number(Object root, String... path) {
        return at(root, path) instanceof Number n ? n.longValue() : 0;
    }
}
