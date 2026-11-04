package gr.gousiosg.javacg.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gr.gousiosg.javacg.devtools.git.Git;
import gr.gousiosg.javacg.devtools.git.SourceMapper;
import gr.gousiosg.javacg.devtools.stack.StackTrace;
import gr.gousiosg.javacg.devtools.stack.StackTraceParser;

/** Tools on a git repository: mapping stack traces to versions, bisecting regressions. */
final class GitTools {

    private final Jobs jobs;

    GitTools(Jobs jobs) {
        this.jobs = jobs;
    }

    List<ToolDef> tools() {
        return List.of(stacktraceMap());
    }

    private ToolDef stacktraceMap() {
        return new ToolDef("stacktrace_map", "Map a stack trace to source", """
                Maps the frames of a stack trace (from a customer log, a test failure) to the sources of a git \
                repository at a given version and checks each one: the line exists, is code, and lies in the \
                method the frame names. Shows each frame's file and surrounding lines at that version. Frames \
                that do not fit mean the trace comes from another version: give several 'versions' or a \
                'tag_pattern' (e.g. 7.4.*) to rank them and find which release produced the trace.""", true,
                Schema.object().string("text", "The stack trace", true)
                        .string("repo", "Git repository (default: the server's directory)", false)
                        .string("ref", "Version to map to: a tag, branch or commit (default HEAD)", false)
                        .strings("versions", "Several refs to rank instead of one", false)
                        .string("tag_pattern", "Rank the tags matching this glob, e.g. '7.4.*'", false)
                        .integer("max_refs", "Most refs ranked", 30)
                        .integer("context", "Source lines shown around each frame line", 2).build(),
                args -> {
                    StackTrace trace = StackTraceParser.parse(args.require("text"))
                            .orElseThrow(() -> new ToolException("No stack trace found in 'text'"));
                    Git git = git(args);
                    List<String> refs = new ArrayList<>(args.strings("versions"));
                    if (args.has("tag_pattern")) {
                        refs.addAll(git.tags(args.require("tag_pattern")));
                        if (refs.isEmpty()) {
                            throw new ToolException("No tag matches " + args.require("tag_pattern"));
                        }
                    }
                    int context = Math.max(0, args.integer("context", 2));
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("repo", git.dir().toString());
                    if (refs.isEmpty()) {
                        m.putAll(view(SourceMapper.map(git, args.string("ref", "HEAD"), trace, context)));
                        return m;
                    }
                    int max = args.integer("max_refs", 30);
                    if (refs.size() > max) {
                        m.put("note", "Ranked the first " + max + " of " + refs.size() + " refs (max_refs)");
                        refs = refs.subList(0, max);
                    }
                    List<SourceMapper.RefResult> ranked;
                    try {
                        ranked = SourceMapper.rank(git, refs, trace);
                    } catch (java.io.IOException e) {
                        throw new ToolException(e.getMessage());
                    }
                    m.put("candidates", ranked.stream().map(r -> {
                        Map<String, Object> c = new LinkedHashMap<>();
                        c.put("ref", r.ref());
                        c.put("commit", r.commit().substring(0, 10));
                        c.put("matching_frames", r.matching());
                        c.put("checked_frames", r.checked());
                        return c;
                    }).toList());
                    SourceMapper.RefResult best = ranked.get(0);
                    if (ranked.size() > 1 && ranked.get(1).score() == best.score() && ranked.get(1).matching() == best.matching()) {
                        m.put("best", "tie: " + ranked.stream().filter(r -> r.score() == best.score()
                                && r.matching() == best.matching()).map(SourceMapper.RefResult::ref).toList()
                                + " fit equally (the frames' files did not change between them)");
                    } else {
                        m.put("best", best.ref());
                    }
                    m.put("details", view(SourceMapper.map(git, best.ref(), trace, context)));
                    return m;
                });
    }

    static Git git(ToolArgs args) {
        Path dir = ToolArgs.path(args.string("repo", args.string("project_dir", ".")));
        Path top = Git.top(dir).orElseThrow(() -> new ToolException("Not a git repository: " + dir));
        return new Git(top);
    }

    private static Map<String, Object> view(SourceMapper.RefResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ref", r.ref());
        m.put("commit", r.commit().substring(0, 10));
        m.put("matching_frames", r.matching());
        m.put("checked_frames", r.checked());
        if (r.checked() == 0) {
            m.put("verdict", "No frame of the trace is in this repository");
        } else if (r.matching() == r.checked()) {
            m.put("verdict", "Every frame found in the repository fits " + r.ref());
        } else {
            m.put("verdict", (r.checked() - r.matching()) + " of " + r.checked() + " frames do not fit " + r.ref()
                    + ": the trace probably comes from another version");
        }
        List<Map<String, Object>> frames = new ArrayList<>();
        int outside = 0;
        for (SourceMapper.FrameResult f : r.frames()) {
            if (f.status() == SourceMapper.Status.NOT_FOUND) {
                outside++;
                continue;
            }
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("frame", f.frame().toString());
            x.put("status", Views.lower(f.status()));
            x.put("path", f.path() + (f.frame().line() > 0 ? ":" + f.frame().line() : ""));
            if (f.enclosing() != null) {
                x.put("in", f.enclosing());
            }
            if (f.note() != null) {
                x.put("note", f.note());
            }
            if (!f.context().isEmpty()) {
                x.put("source", String.join("\n", f.context()));
            }
            frames.add(x);
        }
        m.put("frames", frames);
        m.put("frames_outside_repo", outside);
        return m;
    }
}
