package io.github.elguardian.mcpsettools.issues;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.elguardian.mcpsettools.commons.text.Templates;
import io.github.elguardian.mcpsettools.commons.stack.StackTrace;
import io.github.elguardian.mcpsettools.commons.stack.StackTraces;

/**
 * A known problem and the stack trace that identifies it.
 *
 * @param frames  application frames ({@code Class.method}) of the trace, root cause first
 * @param source  where it came from: {@code manual}, {@code jira-csv}, {@code jira}
 */
public record KnownIssue(String key, String title, String status, List<String> fixedIn, List<String> affects, String url,
        String signature, String signatureId, String exception, String rootException, List<String> frames,
        String messageTemplate, String notes, String source) {

    public KnownIssue {
        fixedIn = fixedIn == null ? List.of() : List.copyOf(fixedIn);
        affects = affects == null ? List.of() : List.copyOf(affects);
        frames = frames == null ? List.of() : List.copyOf(frames);
    }

    /** What identifies a trace, without the issue fields. */
    public record Fingerprint(String signature, String signatureId, String exception, String rootException,
            List<String> frames, String messageTemplate) {

        public static Fingerprint of(StackTrace t, List<String> appPackages) {
            String sig = StackTraces.signature(t, appPackages, 3);
            StackTrace root = t.root();
            List<String> frames = new ArrayList<>();
            List<StackTrace> chain = new ArrayList<>(t.chain());
            java.util.Collections.reverse(chain);
            for (StackTrace s : chain) {
                for (StackTrace.Frame f : s.frames()) {
                    if (frames.size() < 8 && StackTraces.kind(f, appPackages) == StackTraces.Kind.APP
                            && !frames.contains(f.location())) {
                        frames.add(f.location());
                    }
                }
            }
            return new Fingerprint(sig, StackTraces.signatureId(sig), t.exception(), root.exception(), frames,
                    Templates.of(root.message() != null ? root.message() : t.message()));
        }
    }

    public static KnownIssue of(String key, String title, String status, List<String> fixedIn, List<String> affects, String url,
            Fingerprint f, String notes, String source) {
        return new KnownIssue(key, title, status, fixedIn, affects, url, f.signature(), f.signatureId(), f.exception(),
                f.rootException(), f.frames(), f.messageTemplate(), notes, source);
    }

    public Fingerprint fingerprint() {
        return new Fingerprint(signature, signatureId, exception, rootException, frames, messageTemplate);
    }

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("title", title);
        m.put("status", status);
        m.put("fixed_in", fixedIn);
        m.put("affects", affects);
        m.put("url", url);
        m.put("signature", signature);
        m.put("signature_id", signatureId);
        m.put("exception", exception);
        m.put("root_exception", rootException);
        m.put("frames", frames);
        m.put("message_template", messageTemplate);
        m.put("notes", notes);
        m.put("source", source);
        return m;
    }

    @SuppressWarnings("unchecked")
    static KnownIssue fromMap(Map<String, Object> m) {
        return new KnownIssue((String) m.get("key"), (String) m.get("title"), (String) m.get("status"),
                (List<String>) m.get("fixed_in"), (List<String>) m.get("affects"), (String) m.get("url"),
                (String) m.get("signature"), (String) m.get("signature_id"), (String) m.get("exception"),
                (String) m.get("root_exception"), (List<String>) m.get("frames"), (String) m.get("message_template"),
                (String) m.get("notes"), (String) m.get("source"));
    }
}
