package gr.gousiosg.javacg.devtools.git;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gr.gousiosg.javacg.commons.stack.StackTrace;

/**
 * Maps the frames of a stack trace to the sources of a git ref and checks that they fit: the
 * line exists, is code, and lies in the method the frame names. Frames that do not fit mean the
 * trace was produced by another version; ranking several refs by how many frames fit tells which.
 */
public final class SourceMapper {

    public enum Status {
        /** The line is in the method the frame names. */
        MATCH,
        /** The line is in another method, past the end of the file, or not code. */
        MISMATCH,
        /** The file exists but the frame has no line number, or the method is synthetic. */
        UNCHECKED,
        /** No source file for the class at this ref. */
        NOT_FOUND
    }

    public record FrameResult(StackTrace.Frame frame, Status status, String path, String enclosing, String note,
            List<String> context) {
    }

    public record RefResult(String ref, String commit, int matching, int checked, List<FrameResult> frames) {
        public double score() {
            return checked == 0 ? 0 : (double) matching / checked;
        }
    }

    /** Modifiers and annotations, an optional return type, then the name and an opening parenthesis. */
    private static final Pattern METHOD = Pattern.compile(
            "^\\s*(?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*(?:(?:public|protected|private|static|final|synchronized|abstract|native|default|strictfp)\\s+)*"
                    + "(?:<[^>]+>\\s*)?(?<type>[\\w$.]+(?:<[^()]*>)?(?:\\[\\])*\\s+)?(?<name>[\\w$]+)\\s*\\(");
    private static final Pattern STATIC_INIT = Pattern.compile("^\\s*static\\s*\\{");
    private static final Pattern NOT_A_DECLARATION = Pattern.compile(
            "^\\s*(?:if|for|while|switch|catch|synchronized|return|new|else|do|try|throw|assert|case|yield|super|this)\\b.*");
    private static final Pattern LAMBDA = Pattern.compile("^lambda\\$(.+?)\\$\\d+$");

    private SourceMapper() {
    }

    public static RefResult map(Git git, String ref, StackTrace trace, int context) throws IOException, InterruptedException {
        String commit = git.commit(ref);
        Map<String, List<String>> byName = new HashMap<>();
        for (String p : git.files(commit)) {
            int slash = p.lastIndexOf('/');
            byName.computeIfAbsent(p.substring(slash + 1), k -> new ArrayList<>()).add(p);
        }
        Map<String, Optional<String[]>> cache = new HashMap<>();
        List<FrameResult> results = new ArrayList<>();
        int matching = 0;
        int checked = 0;
        for (StackTrace t : trace.chain()) {
            for (StackTrace.Frame f : t.frames()) {
                FrameResult r = frame(git, commit, f, byName, cache, context);
                results.add(r);
                if (r.status() == Status.MATCH) {
                    matching++;
                }
                if (r.status() == Status.MATCH || r.status() == Status.MISMATCH) {
                    checked++;
                }
            }
        }
        return new RefResult(ref, commit, matching, checked, results);
    }

    /** Refs ordered by the share of checkable frames that fit, best first. */
    public static List<RefResult> rank(Git git, List<String> refs, StackTrace trace) throws IOException, InterruptedException {
        List<RefResult> all = new ArrayList<>();
        for (String ref : refs) {
            all.add(map(git, ref, trace, 0));
        }
        all.sort(Comparator.comparingDouble(RefResult::score).reversed().thenComparing(Comparator.comparingInt(RefResult::matching)
                .reversed()));
        return all;
    }

    private static FrameResult frame(Git git, String commit, StackTrace.Frame f, Map<String, List<String>> byName,
            Map<String, Optional<String[]>> cache, int context) throws IOException, InterruptedException {
        if (f.file() == null || f.isNative()) {
            return new FrameResult(f, Status.NOT_FOUND, null, null, "no source file in the frame", List.of());
        }
        String cls = f.className();
        int dot = cls.lastIndexOf('.');
        String key = (dot < 0 ? "" : cls.substring(0, dot).replace('.', '/') + "/") + f.file();
        String path = null;
        for (String p : byName.getOrDefault(f.file(), List.of())) {
            if (p.equals(key) || p.endsWith("/" + key)) {
                path = p;
                break;
            }
        }
        if (path == null) {
            return new FrameResult(f, Status.NOT_FOUND, null, null, "not in this repository", List.of());
        }
        String p = path;
        Optional<String[]> lines = cache.computeIfAbsent(p, k -> {
            try {
                return git.show(commit, k).map(s -> s.split("\n", -1));
            } catch (IOException | InterruptedException e) {
                return Optional.empty();
            }
        });
        if (lines.isEmpty()) {
            return new FrameResult(f, Status.NOT_FOUND, p, null, "cannot read the file", List.of());
        }
        String[] src = lines.get();
        int line = f.line();
        if (line <= 0) {
            return new FrameResult(f, Status.UNCHECKED, p, null, "no line number", List.of());
        }
        if (line > src.length) {
            return new FrameResult(f, Status.MISMATCH, p, null, "the file has only " + src.length + " lines", List.of());
        }
        List<String> ctx = new ArrayList<>();
        for (int i = Math.max(1, line - context); i <= Math.min(src.length, line + context); i++) {
            ctx.add((i == line ? ">" : " ") + String.format("%5d  ", i) + src[i - 1]);
        }
        String expected = expected(f);
        if (expected == null) {
            return new FrameResult(f, Status.UNCHECKED, p, null, "synthetic method", ctx);
        }
        String code = src[line - 1].strip();
        if (code.isEmpty() || code.startsWith("//") || code.startsWith("*") || code.startsWith("/*") || code.equals("}")
                && !expected.equals("<clinit>")) {
            return new FrameResult(f, Status.MISMATCH, p, enclosing(src, line), "line " + line + " is not code: '" + code + "'", ctx);
        }
        String enclosing = enclosing(src, line);
        boolean ok = expected.equals(enclosing);
        return new FrameResult(f, ok ? Status.MATCH : Status.MISMATCH, p, enclosing,
                ok ? null : "line " + line + " is in " + (enclosing == null ? "no method" : enclosing + "()") + ", the frame says "
                        + expected + "()", ctx);
    }

    /** The member name a frame's line must be in: lambdas map to their enclosing method, constructors to the class. */
    static String expected(StackTrace.Frame f) {
        String m = f.method();
        if (m.equals("<init>")) {
            String simple = f.className().substring(f.className().lastIndexOf('.') + 1);
            String inner = simple.substring(simple.lastIndexOf('$') + 1);
            return inner.matches("\\d+") ? null : inner;
        }
        if (m.equals("<clinit>")) {
            return "<clinit>";
        }
        Matcher l = LAMBDA.matcher(m);
        if (l.matches()) {
            String name = l.group(1);
            return name.equals("static") || name.equals("new") ? null : name;
        }
        if (m.startsWith("access$") || m.contains("$")) {
            return null;
        }
        return m;
    }

    /**
     * The name of the nearest method, constructor or static block declared at or above
     * {@code line}. A declaration has a return type, or is a constructor (a capitalized name);
     * abstract signatures and calls ({@code process(order);}) are not declarations.
     */
    static String enclosing(String[] src, int line) {
        for (int i = line - 1; i >= 0; i--) {
            String s = src[i];
            if (STATIC_INIT.matcher(s).find()) {
                return "<clinit>";
            }
            if (NOT_A_DECLARATION.matcher(s).matches() || s.strip().endsWith(";")) {
                continue;
            }
            Matcher m = METHOD.matcher(s);
            if (!m.lookingAt()) {
                continue;
            }
            String name = m.group("name");
            boolean typed = m.group("type") != null && !m.group("type").isBlank();
            if (typed || Character.isUpperCase(name.charAt(0))) {
                return name;
            }
        }
        return null;
    }
}
