package gr.gousiosg.javacg.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import gr.gousiosg.javacg.rt.Symbols;

/**
 * Instruments the classes selected by {@link AgentConfig}. Any failure leaves the class
 * unchanged. Frames are kept, not recomputed: the only new control flow is one catch-all
 * handler whose frame is written explicitly, so no class loading is needed during transform.
 */
final class Transformer implements ClassFileTransformer {

    private final AgentConfig config;

    Transformer(AgentConfig config) {
        this.config = config;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] bytes) {
        if (className == null) {
            return null;
        }
        String name = className.replace('/', '.');
        if (!config.shouldInstrument(name)) {
            return null;
        }
        try {
            byte[] result = instrument(bytes, config);
            if (result != null && config.verbose) {
                System.err.println("[javacg] instrumented " + name);
            }
            return result;
        } catch (Throwable t) {
            System.err.println("[javacg] cannot instrument " + name + ", left unchanged: " + t);
            return null;
        }
    }

    /** Returns the instrumented class, or null to keep the original. */
    static byte[] instrument(byte[] bytes, AgentConfig config) {
        ClassReader cr = new ClassReader(bytes);
        if ((cr.getAccess() & Opcodes.ACC_MODULE) != 0) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            private String owner;
            private int version;

            @Override
            public void visit(int v, int access, String name, String signature, String superName,
                    String[] interfaces) {
                owner = name;
                version = v & 0xFFFF;
                super.visit(v, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_BRIDGE)) != 0) {
                    return mv;
                }
                int id = Symbols.method(owner, name, descriptor);
                return new MethodInstrumenter(mv, access, name, descriptor, owner, id, version, config);
            }
        }, ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }
}
