package gr.gousiosg.javacg.agent;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.ArrayList;
import java.util.List;

import gr.gousiosg.javacg.rt.Recorder;
import gr.gousiosg.javacg.rt.RecorderControl;
import gr.gousiosg.javacg.rt.Watch;

/** Second boot stage, loaded once the runtime is on the bootstrap class path. */
final class Installer {

    private Installer() {
    }

    static void install(AgentConfig config, Instrumentation inst, boolean attached) {
        config.warnings.forEach(w -> System.err.println("[javacg] " + w));
        Recorder.configure(config.out, config.args, config.verbose);
        if (config.recordAtStartup) {
            Recorder.start(config.label);
        }
        Transformer transformer = new Transformer(config);
        inst.addTransformer(transformer, true);
        if (attached) {
            retransformLoaded(config, inst);
        }
        Watch.controller(new WatchControl(inst));
        if (config.jmxDelay != AgentConfig.JMX_OFF) {
            RecorderControl.registerLater(config.jmxDelay);
        }
    }

    /** After a late attach: instrument the matching classes that are already loaded. */
    private static void retransformLoaded(AgentConfig config, Instrumentation inst) {
        List<Class<?>> targets = new ArrayList<>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (!c.isArray() && !c.isPrimitive() && !c.isHidden() && inst.isModifiableClass(c)
                    && config.shouldInstrument(c.getName())) {
                targets.add(c);
            }
        }
        for (Class<?> c : targets) {
            try {
                inst.retransformClasses(c);
            } catch (UnmodifiableClassException | RuntimeException | LinkageError e) {
                System.err.println("[javacg] cannot retransform " + c.getName() + ": " + e);
            }
        }
    }
}
