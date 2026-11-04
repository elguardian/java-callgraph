package io.github.elguardian.mcpsettools.stat;

import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.objectweb.asm.Type;

import io.github.elguardian.mcpsettools.core.MethodId;

/**
 * Writes the original {@code mcp-set-tools-static} text format:
 *
 * <pre>
 * C:class1 class2
 * M:class1:method1(arg_types) (calltype)class2:method2(arg_types)
 * </pre>
 *
 * One {@code M:} line per call instruction, naming the symbolic reference (not the resolved
 * targets), so the output matches the old tool line for line.
 */
public final class LegacyTextWriter {

    private LegacyTextWriter() {
    }

    public static void write(StaticResult result, Writer out) {
        PrintWriter w = new PrintWriter(out);
        Map<String, List<CallSite>> byClass = new HashMap<>();
        for (CallSite s : result.callSites()) {
            byClass.computeIfAbsent(s.caller().owner(), k -> new ArrayList<>()).add(s);
        }
        for (ClassInfo c : result.appClasses()) {
            String self = c.name().replace('/', '.');
            c.referencedClasses().stream().sorted().forEach(r -> w.println("C:" + self + " " + r));
            for (CallSite s : byClass.getOrDefault(c.name(), List.of())) {
                w.println(methodLine(s));
            }
        }
        w.flush();
    }

    static String methodLine(CallSite s) {
        MethodId caller = s.caller();
        return "M:" + caller.className() + ":" + caller.name() + "(" + caller.javaArguments() + ") ("
                + s.kind().code() + ")" + Type.getObjectType(s.owner()).getClassName() + ":" + s.name() + "("
                + arguments(s.descriptor()) + ")";
    }

    private static String arguments(String descriptor) {
        StringJoiner j = new StringJoiner(",");
        for (Type t : Type.getArgumentTypes(descriptor)) {
            j.add(t.getClassName());
        }
        return j.toString();
    }
}
