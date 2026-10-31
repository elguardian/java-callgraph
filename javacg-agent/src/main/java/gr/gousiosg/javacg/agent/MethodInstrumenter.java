package gr.gousiosg.javacg.agent;

import java.util.Set;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

import gr.gousiosg.javacg.rt.Symbols;

/**
 * Adds the probes to one method:
 *
 * <pre>
 * Probe.enter(id)                          at entry (constructors: before the super() call)
 * Probe.args(new Object[]{...}, id)        if argument capture is on
 * Probe.lock(this | Owner.class)           synchronized methods, if lock events are on
 * try {
 *     body                                 monitorenter/exit and Lock.lock/unlock calls
 *                                          get Probe.lock/unlock first, if lock events are on
 *     Probe.unlock(..); Probe.exit(id)     before every return
 * } catch (Throwable t) {                  appended last in the exception table
 *     Probe.unlock(..); Probe.exitThrow(t, id); throw t;
 * }
 * </pre>
 *
 * In constructors the try block starts after the super()/this() call, as the verifier
 * requires. Probe code goes straight to the next visitor, bypassing AdviceAdapter's
 * constructor stack simulation; every probe sequence is stack-neutral.
 */
final class MethodInstrumenter extends AdviceAdapter {

    static final String PROBE = "gr/gousiosg/javacg/rt/Probe";
    private static final String MONITOR_DESC = "(Ljava/lang/Object;)V";
    private static final Set<String> LOCK_TYPES = Set.of("java/util/concurrent/locks/Lock",
            "java/util/concurrent/locks/ReentrantLock", "java/util/concurrent/locks/ReentrantReadWriteLock$ReadLock",
            "java/util/concurrent/locks/ReentrantReadWriteLock$WriteLock");

    private final int id;
    private final String owner;
    private final boolean constructor;
    private final boolean isStatic;
    private final boolean syncLocks;
    private final boolean locks;
    private final boolean captureArgs;
    private final boolean writeFrames;
    private final boolean classLiterals;
    private final Type[] argumentTypes;
    private Label tryStart;
    private boolean lineSeen;

    MethodInstrumenter(MethodVisitor mv, int access, String name, String descriptor, String owner, int id,
            int classVersion, AgentConfig config) {
        super(Opcodes.ASM9, mv, access, name, descriptor);
        this.id = id;
        this.owner = owner;
        this.constructor = name.equals("<init>");
        this.isStatic = (access & Opcodes.ACC_STATIC) != 0;
        this.locks = config.locks;
        this.classLiterals = classVersion >= Opcodes.V1_5;
        this.syncLocks = locks && (access & Opcodes.ACC_SYNCHRONIZED) != 0 && (!isStatic || classLiterals);
        this.captureArgs = !config.args.equals("none");
        this.writeFrames = classVersion >= Opcodes.V1_6;
        this.argumentTypes = Type.getArgumentTypes(descriptor);
    }

    @Override
    public void visitCode() {
        super.visitCode();
        if (constructor) {
            probe("enter", "(I)V", true);
        }
    }

    @Override
    protected void onMethodEnter() {
        if (!constructor) {
            probe("enter", "(I)V", true);
        }
        if (captureArgs) {
            argumentArray();
            pushInt(id);
            mv.visitMethodInsn(INVOKESTATIC, PROBE, "args", "([Ljava/lang/Object;I)V", false);
        }
        if (syncLocks) {
            loadMonitor();
            mv.visitMethodInsn(INVOKESTATIC, PROBE, "lock", MONITOR_DESC, false);
        }
        tryStart = new Label();
        mv.visitLabel(tryStart);
    }

    @Override
    protected void onMethodExit(int opcode) {
        if (opcode == ATHROW) {
            return; // reported by the catch-all handler
        }
        if (syncLocks) {
            loadMonitor();
            mv.visitMethodInsn(INVOKESTATIC, PROBE, "unlock", MONITOR_DESC, false);
        }
        probe("exit", "(I)V", true);
    }

    @Override
    public void visitLineNumber(int line, Label start) {
        if (!lineSeen) {
            lineSeen = true;
            Symbols.line(id, line);
        }
        super.visitLineNumber(line, start);
    }

    @Override
    public void visitInsn(int opcode) {
        if (locks && (opcode == MONITORENTER || opcode == MONITOREXIT)) {
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESTATIC, PROBE, opcode == MONITORENTER ? "lock" : "unlock", MONITOR_DESC, false);
        }
        super.visitInsn(opcode);
    }

    @Override
    public void visitMethodInsn(int opcode, String insnOwner, String name, String descriptor, boolean isInterface) {
        if (locks && descriptor.equals("()V") && LOCK_TYPES.contains(insnOwner)) {
            String probe = switch (name) {
                case "lock", "lockInterruptibly" -> "lock";
                case "unlock" -> "unlock";
                default -> null;
            };
            if (probe != null) {
                mv.visitInsn(DUP);
                mv.visitMethodInsn(INVOKESTATIC, PROBE, probe, MONITOR_DESC, false);
            }
        }
        super.visitMethodInsn(opcode, insnOwner, name, descriptor, isInterface);
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
                Object[] handlerLocals = syncLocks && !isStatic ? new Object[] {owner} : new Object[0];
                mv.visitFrame(F_NEW, handlerLocals.length, handlerLocals, 1, new Object[] {"java/lang/Throwable"});
            }
            if (syncLocks) {
                loadMonitor();
                mv.visitMethodInsn(INVOKESTATIC, PROBE, "unlock", MONITOR_DESC, false);
            }
            mv.visitInsn(DUP);
            pushInt(id);
            mv.visitMethodInsn(INVOKESTATIC, PROBE, "exitThrow", "(Ljava/lang/Throwable;I)V", false);
            mv.visitInsn(ATHROW);
        }
        super.visitMaxs(maxStack, maxLocals);
    }

    private void probe(String method, String descriptor, boolean withId) {
        if (withId) {
            pushInt(id);
        }
        mv.visitMethodInsn(INVOKESTATIC, PROBE, method, descriptor, false);
    }

    private void loadMonitor() {
        if (isStatic) {
            mv.visitLdcInsn(Type.getObjectType(owner));
        } else {
            mv.visitVarInsn(ALOAD, 0);
        }
    }

    /** Pushes an Object[] holding the (boxed) arguments. */
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
