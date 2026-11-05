package io.github.elguardian.mcpsettools.commons.stack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds and parses Java stack traces in text: {@code Throwable.printStackTrace} output, including
 * {@code Caused by:} chains, {@code Suppressed:} blocks, {@code ... N more}, JDK 9+ module
 * prefixes ({@code java.base/}, {@code app//}) and logback/log4j frame suffixes ({@code ~[jar:1.0]}).
 */
public final class StackTraceParser {

    private static final Pattern HEADER = Pattern.compile(
            "^(?<indent>\\s*)(?:Exception in thread \"[^\"]*\" )?(?<kind>Caused by: |Suppressed: |Wrapped by: )?"
                    + "(?<cls>(?:[A-Za-z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*)(?::(?: ?(?<msg>.*))?)?$");
    private static final Pattern FRAME = Pattern.compile(
            "^\\s*at\\s+(?:(?<p1>[^\\s/(]*)/)?(?:(?<p2>[^\\s/(]*)/)?(?<cls>[^\\s/(]+)\\.(?<method>[^\\s.(/]+)"
                    + "\\((?<src>[^)]*)\\).*$");
    private static final Pattern MORE = Pattern.compile(
            "^\\s*\\.\\.\\. (?<n>\\d+) (?:more|common frames omitted)\\s*$");

    private StackTraceParser() {
    }

    /** The first stack trace in {@code text}; a header without frames counts when it is the first line. */
    public static Optional<StackTrace> parse(String text) {
        List<StackTrace> all = parse(text, false);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /** Every stack trace in {@code text} that has at least one frame. */
    public static List<StackTrace> parseAll(String text) {
        return parse(text, true);
    }

    private static final class Builder {
        final int indent;
        final String exception;
        final StringBuilder message;
        final List<StackTrace.Frame> frames = new ArrayList<>();
        final List<Builder> suppressed = new ArrayList<>();
        Builder cause;
        int omitted;
        boolean messageOpen = true;

        Builder(int indent, String exception, String message) {
            this.indent = indent;
            this.exception = exception;
            this.message = message == null ? null : new StringBuilder(message);
        }

        StackTrace build() {
            return new StackTrace(exception, message == null ? null : message.toString().strip(), frames, omitted,
                    cause == null ? null : cause.build(), suppressed.stream().map(Builder::build).toList());
        }

        boolean hasFrames() {
            for (Builder b = this; b != null; b = b.cause) {
                if (!b.frames.isEmpty()) {
                    return true;
                }
            }
            return false;
        }
    }

    private static List<StackTrace> parse(String text, boolean requireFrames) {
        List<StackTrace> result = new ArrayList<>();
        Builder root = null;
        Deque<Builder> stack = new ArrayDeque<>();
        List<String> pendingMessage = new ArrayList<>();
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher f = FRAME.matcher(line);
            if (root != null && f.matches()) {
                Builder top = stack.peek();
                if (top.messageOpen && !pendingMessage.isEmpty() && top.message != null) {
                    pendingMessage.forEach(m -> top.message.append('\n').append(m));
                }
                pendingMessage.clear();
                top.messageOpen = false;
                top.frames.add(frame(f));
                continue;
            }
            Matcher more = MORE.matcher(line);
            if (root != null && more.matches()) {
                stack.peek().omitted = Integer.parseInt(more.group("n"));
                stack.peek().messageOpen = false;
                continue;
            }
            Matcher h = HEADER.matcher(line);
            if (h.matches() && looksLikeThrowable(h.group("cls"), h.group("kind"))) {
                String kind = h.group("kind");
                int indent = indent(h.group("indent"));
                Builder b = new Builder(indent, h.group("cls"), h.group("msg"));
                if (root != null && kind != null && !kind.startsWith("Wrapped")) {
                    pendingMessage.clear();
                    if (kind.startsWith("Caused")) {
                        while (stack.size() > 1 && stack.peek().indent > indent) {
                            stack.pop();
                        }
                        Builder parent = stack.pop();
                        parent.cause = b;
                        stack.push(b);
                    } else {
                        while (stack.size() > 1 && stack.peek().indent >= indent) {
                            stack.pop();
                        }
                        stack.peek().suppressed.add(b);
                        stack.push(b);
                    }
                    continue;
                }
                if (kind == null || root == null) {
                    finish(root, requireFrames, result);
                    root = b;
                    stack.clear();
                    stack.push(b);
                    pendingMessage.clear();
                    continue;
                }
            }
            if (root != null && stack.peek().messageOpen && stack.peek().frames.isEmpty() && i < lines.length - 1) {
                pendingMessage.add(line);
                continue;
            }
            finish(root, requireFrames, result);
            root = null;
            stack.clear();
            pendingMessage.clear();
            if (!requireFrames && !result.isEmpty()) {
                return result;
            }
        }
        finish(root, requireFrames, result);
        return result;
    }

    private static void finish(Builder root, boolean requireFrames, List<StackTrace> into) {
        if (root != null && (!requireFrames || root.hasFrames())) {
            into.add(root.build());
        }
    }

    /** Class names alone are common in text; without frames a header must name a Throwable-like class. */
    private static boolean looksLikeThrowable(String cls, String kind) {
        if (kind != null) {
            return true;
        }
        String simple = cls.substring(cls.lastIndexOf('.') + 1);
        return simple.endsWith("Exception") || simple.endsWith("Error") || simple.endsWith("Throwable")
                || simple.endsWith("Failure") || simple.endsWith("Fault") || simple.endsWith("Problem");
    }

    private static int indent(String ws) {
        int n = 0;
        for (char c : ws.toCharArray()) {
            n += c == '\t' ? 8 : 1;
        }
        return n;
    }

    /** One {@code at ...} line as a frame, if it is one. */
    public static Optional<StackTrace.Frame> parseFrame(String line) {
        Matcher f = FRAME.matcher(line);
        return f.matches() ? Optional.of(frame(f)) : Optional.empty();
    }

    private static StackTrace.Frame frame(Matcher f) {
        String p1 = f.group("p1");
        String p2 = f.group("p2");
        // "app//a.B.m" (loader, no module), "java.base/a.B.m" (module), "loader/module@1/a.B.m"
        String module = p2 != null ? (p2.isEmpty() ? null : p2) : (p1 == null || p1.isEmpty() ? null : p1);
        if (p2 != null && p2.isEmpty() && p1 != null) {
            module = null;
        }
        String src = f.group("src");
        String file = null;
        int line = -1;
        if (src.equals("Native Method")) {
            file = src;
        } else if (!src.isEmpty() && !src.equals("Unknown Source")) {
            int colon = src.lastIndexOf(':');
            if (colon > 0) {
                file = src.substring(0, colon);
                try {
                    line = Integer.parseInt(src.substring(colon + 1).strip());
                } catch (NumberFormatException e) {
                    file = src;
                }
            } else {
                file = src;
            }
        }
        return new StackTrace.Frame(f.group("cls"), f.group("method"), file, line, module);
    }
}
