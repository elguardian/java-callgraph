package gr.gousiosg.javacg.commons.text;

import java.util.regex.Pattern;

/** Message templates: the first line with its variable parts replaced by {@code <*>}, so repeated messages group. */
public final class Templates {

    private static final Pattern VARIABLE = Pattern.compile(String.join("|",
            "<[^<>]*>", "\"[^\"]*\"", "'[^']*'",
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b",
            "\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b", "\\b0x[0-9a-fA-F]+\\b", "@[0-9a-fA-F]{4,}\\b",
            "\\b[0-9a-fA-F]{16,}\\b", "(?<![\\w.])-?\\d+(?:[.,]\\d+)*(?:ms|s|ns|us|µs|KB|MB|GB|B|%)?(?![\\w.])"));

    private Templates() {
    }

    public static String of(String message) {
        if (message == null) {
            return "";
        }
        int nl = message.indexOf('\n');
        String first = (nl < 0 ? message : message.substring(0, nl)).strip();
        return VARIABLE.matcher(first).replaceAll("<*>");
    }
}
