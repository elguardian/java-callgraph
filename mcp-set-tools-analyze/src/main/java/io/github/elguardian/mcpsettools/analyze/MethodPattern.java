package io.github.elguardian.mcpsettools.analyze;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.github.elguardian.mcpsettools.core.MethodId;

/**
 * Selects methods by name. The pattern is matched against {@code pkg.Class.method} and may
 * contain {@code *} (any characters). A trailing {@code (descriptor)} must match the JVM
 * descriptor exactly; a trailing {@code (java, types)} matches the Java parameter list.
 *
 * <pre>
 * com.example.Dao.save            every overload of save
 * com.example.Dao.save(I)V        one overload, JVM descriptor
 * com.example.Dao.save(int)       one overload, Java parameter types
 * com.example.*.save*             any class in or below com.example
 * *Authorizer.authorize
 * </pre>
 */
public final class MethodPattern {

    private final String source;
    private final Pattern name;
    private final String parameters;

    private MethodPattern(String source, Pattern name, String parameters) {
        this.source = source;
        this.name = name;
        this.parameters = parameters;
    }

    public static MethodPattern parse(String pattern) {
        String p = pattern.strip();
        String params = null;
        int paren = p.indexOf('(');
        if (paren >= 0) {
            params = p.substring(paren);
            p = p.substring(0, paren);
        }
        if (p.isEmpty()) {
            throw new IllegalArgumentException("Empty method pattern: " + pattern);
        }
        String regex = Arrays.stream(p.replace('/', '.').split("\\*", -1))
                .map(part -> part.isEmpty() ? "" : Pattern.quote(part)).collect(Collectors.joining(".*"));
        return new MethodPattern(pattern, Pattern.compile(regex), params);
    }

    public boolean matches(MethodId m) {
        if (!name.matcher(m.className() + "." + m.name()).matches()) {
            return false;
        }
        if (parameters == null) {
            return true;
        }
        if (parameters.equals(m.descriptor()) || m.descriptor().startsWith(parameters) && parameters.endsWith(")")) {
            return true;
        }
        return parameters.replace(" ", "").equals("(" + m.javaArguments() + ")");
    }

    public Set<MethodId> select(Collection<MethodId> methods) {
        return methods.stream().filter(this::matches).collect(Collectors.toSet());
    }

    @Override
    public String toString() {
        return source;
    }
}
