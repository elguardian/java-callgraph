package io.github.elguardian.mcpsettools.stat;

import java.util.Set;

import io.github.elguardian.mcpsettools.core.CallKind;
import io.github.elguardian.mcpsettools.core.MethodId;

/**
 * One call instruction in application code.
 *
 * @param caller      method containing the instruction
 * @param index       instruction index in the caller's {@code InsnList}
 * @param line        source line, or -1
 * @param kind        call kind
 * @param owner       symbolic reference owner (internal name). For {@code invokedynamic} this
 *                    is the type the call site produces (the functional interface for lambdas)
 * @param name        symbolic method name ({@code invokedynamic}: the call site name)
 * @param descriptor  symbolic descriptor ({@code invokedynamic}: the call site descriptor)
 * @param targets     methods this site may invoke under the chosen algorithm; empty when the
 *                    site is not linked into the graph (e.g. string concatenation)
 */
public record CallSite(MethodId caller, int index, int line, CallKind kind, String owner, String name,
        String descriptor, Set<MethodId> targets) {
}
