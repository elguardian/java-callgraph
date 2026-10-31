package gr.gousiosg.javacg.core.io;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Minimal streaming JSON writer, so the core model does not pull in a JSON library. Output is
 * indented with two spaces. Methods throw {@link UncheckedIOException} on I/O failures.
 */
public final class JsonWriter implements Closeable {

    private final Writer out;
    /** One entry per open container: true while it is still empty. */
    private final Deque<Boolean> empty = new ArrayDeque<>();
    private boolean afterName;

    public JsonWriter(Writer out) {
        this.out = out;
    }

    public JsonWriter beginObject() {
        return open('{');
    }

    public JsonWriter endObject() {
        return close('}');
    }

    public JsonWriter beginArray() {
        return open('[');
    }

    public JsonWriter endArray() {
        return close(']');
    }

    public JsonWriter name(String name) {
        separator();
        string(name);
        write(": ");
        afterName = true;
        return this;
    }

    public JsonWriter value(String s) {
        separator();
        if (s == null) {
            write("null");
        } else {
            string(s);
        }
        return this;
    }

    public JsonWriter value(long n) {
        separator();
        write(Long.toString(n));
        return this;
    }

    public JsonWriter value(double n) {
        separator();
        write(Double.isFinite(n) ? Double.toString(n) : "null");
        return this;
    }

    public JsonWriter value(boolean b) {
        separator();
        write(Boolean.toString(b));
        return this;
    }

    public JsonWriter field(String name, String value) {
        return name(name).value(value);
    }

    public JsonWriter field(String name, long value) {
        return name(name).value(value);
    }

    public JsonWriter field(String name, boolean value) {
        return name(name).value(value);
    }

    public void flush() {
        try {
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JsonWriter open(char c) {
        separator();
        write(String.valueOf(c));
        empty.push(Boolean.TRUE);
        return this;
    }

    private JsonWriter close(char c) {
        boolean wasEmpty = empty.pop();
        if (!wasEmpty) {
            newline();
        }
        write(String.valueOf(c));
        if (empty.isEmpty()) {
            write("\n");
        }
        return this;
    }

    /** Emits the comma/newline/indent that must precede a value or a name. */
    private void separator() {
        if (afterName) {
            afterName = false;
            return;
        }
        if (empty.isEmpty()) {
            return;
        }
        if (!empty.peek()) {
            write(",");
        }
        empty.pop();
        empty.push(Boolean.FALSE);
        newline();
    }

    private void newline() {
        StringBuilder sb = new StringBuilder("\n");
        sb.append("  ".repeat(empty.size()));
        write(sb.toString());
    }

    private void string(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        write(sb.append('"').toString());
    }

    private void write(String s) {
        try {
            out.write(s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
