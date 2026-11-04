package io.github.elguardian.mcpsettools.agent;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * Inserts the watch probes into one method:
 *
 * <pre>
 * Watch.enter(new Object[]{args...} | null, id)      at entry (constructors: after super())
 * Watch.exit(boxed result | Watch.VOID, id)           before every return
 * catch (Throwable t) { Watch.thrown(t, id); throw t; }   around the body
 * </pre>
 *
 * Like {@link MethodInstrumenter} it adds no local variables: every probe is stack-neutral and
 * the handler's frame is written explicitly, so frames need not be recomputed.
 */
final class WatchInstrumenter extends AdviceAdapter {

    static final String WATCH = "io/github/elguardian/mcpsettools/rt/Watch";

    private final int id;
    private final boolean captureArgs;
    private final boolean isStatic;
    private final boolean writeFrames;
    private final Type[] argumentTypes;
    private final Type returnType;
    private Label tryStart;

    WatchInstrumenter(MethodVisitor mv, int access, String name, String descriptor, int id, boolean captureArgs,
            int classVersion) {
        super(Opcodes.ASM9, mv, access, name, descriptor);
        this.id = id;
        this.captureArgs = captureArgs;
        this.isStatic = (access & Opcodes.ACC_STATIC) != 0;
        this.writeFrames = classVersion >= Opcodes.V1_6;
        this.argumentTypes = Type.getArgumentTypes(descriptor);
        this.returnType = Type.getReturnType(descriptor);
    }

    @Override
    protected void onMethodEnter() {
        if (captureArgs) {
            argumentArray();
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        pushInt(id);
        mv.visitMethodInsn(INVOKESTATIC, WATCH, "enter", "([Ljava/lang/Object;I)V", false);
        tryStart = new Label();
        mv.visitLabel(tryStart);
    }

    @Override
    protected void onMethodExit(int opcode) {
        if (opcode == ATHROW) {
            return; // the catch-all handler reports it
        }
        if (opcode == RETURN) {
            mv.visitFieldInsn(GETSTATIC, WATCH, "VOID", "Ljava/lang/Object;");
        } else if (returnType.getSize() == 2) {
            mv.visitInsn(DUP2);
            boxValue(returnType);
        } else {
            mv.visitInsn(DUP);
            boxValue(returnType);
        }
        pushInt(id);
        mv.visitMethodInsn(INVOKESTATIC, WATCH, "exit", "(Ljava/lang/Object;I)V", false);
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        if (tryStart != null) {
            Label tryEnd = new Label();
            Label handler = new Label();
            mv.visitLabel(tryEnd);
            mv.visitTryCatchBlock(tryStart, tryEnd, handler, null);
            mv.visitLabel(handler);
            if (writeFrames) {
                mv.visitFrame(F_NEW, 0, new Object[0], 1, new Object[] { "java/lang/Throwable" });
            }
            mv.visitInsn(DUP);
            pushInt(id);
            mv.visitMethodInsn(INVOKESTATIC, WATCH, "thrown", "(Ljava/lang/Throwable;I)V", false);
            mv.visitInsn(ATHROW);
        }
        super.visitMaxs(maxStack, maxLocals);
    }

    private void argumentArray() {
        pushInt(argumentTypes.length);
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        int slot = isStatic ? 0 : 1;
        for (int i = 0; i < argumentTypes.length; i++) {
            Type t = argumentTypes[i];
            mv.visitInsn(DUP);
            pushInt(i);
            mv.visitVarInsn(t.getOpcode(ILOAD), slot);
            boxValue(t);
            mv.visitInsn(AASTORE);
            slot += t.getSize();
        }
    }

    private void boxValue(Type t) {
        String wrapper = switch (t.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.CHAR -> "java/lang/Character";
            case Type.BYTE -> "java/lang/Byte";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.FLOAT -> "java/lang/Float";
            case Type.LONG -> "java/lang/Long";
            case Type.DOUBLE -> "java/lang/Double";
            default -> null;
        };
        if (wrapper != null) {
            mv.visitMethodInsn(INVOKESTATIC, wrapper, "valueOf", "(" + t.getDescriptor() + ")L" + wrapper + ";", false);
        }
    }

    private void pushInt(int v) {
        if (v >= -1 && v <= 5) {
            mv.visitInsn(ICONST_0 + v);
        } else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
            mv.visitIntInsn(BIPUSH, v);
        } else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
            mv.visitIntInsn(SIPUSH, v);
        } else {
            mv.visitLdcInsn(v);
        }
    }
}
