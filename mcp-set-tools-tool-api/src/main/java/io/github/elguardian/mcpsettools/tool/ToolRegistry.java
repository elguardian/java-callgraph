package io.github.elguardian.mcpsettools.tool;

import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * The tools of a host: providers found through ServiceLoader (and given explicitly), with
 * disabled tools removed, duplicate names reported, and one dispatch path for every caller.
 */
public final class ToolRegistry {

    /** Result of a call: a JSON-friendly value (maps, lists, strings, numbers, booleans) or an error message. */
    public record Result(Object value, String error) {
        public boolean isError() {
            return error != null;
        }
    }

    private final ToolContext context;
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Map<String, String> providerOf = new LinkedHashMap<>();
    private final List<String> instructions = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private ToolRegistry(ToolContext context) {
        this.context = context;
    }

    /**
     * Providers of {@code loader} plus {@code extra}; {@code disabled} holds tool names and
     * {@code provider:<id>} entries.
     */
    public static ToolRegistry load(ClassLoader loader, ToolContext context, Collection<String> disabled,
            List<ToolProvider> extra) {
        List<ToolProvider> providers = new ArrayList<>(extra);
        ServiceLoader<ToolProvider> sl = ServiceLoader.load(ToolProvider.class, loader);
        var it = sl.iterator();
        ToolRegistry r = new ToolRegistry(context);
        while (true) {
            try {
                if (!it.hasNext()) {
                    break;
                }
                providers.add(it.next());
            } catch (ServiceConfigurationError e) {
                r.warnings.add("cannot load a tool provider: " + e.getMessage());
            }
        }
        r.add(providers, Set.copyOf(disabled));
        return r;
    }

    public static ToolRegistry of(ToolContext context, List<ToolProvider> providers, Collection<String> disabled) {
        ToolRegistry r = new ToolRegistry(context);
        r.add(providers, Set.copyOf(disabled));
        return r;
    }

    private void add(List<ToolProvider> providers, Set<String> disabled) {
        Set<String> seenProviders = new java.util.HashSet<>();
        for (ToolProvider p : providers) {
            if (!seenProviders.add(p.getClass().getName() + ":" + p.id())) {
                continue;
            }
            if (disabled.contains("provider:" + p.id())) {
                continue;
            }
            List<Tool> list;
            try {
                list = p.tools(context);
            } catch (RuntimeException | ServiceConfigurationError | LinkageError e) {
                warnings.add("tool provider " + p.id() + " failed and was skipped: " + e);
                continue;
            }
            boolean any = false;
            for (Tool t : list) {
                if (disabled.contains(t.name())) {
                    continue;
                }
                if (tools.containsKey(t.name())) {
                    warnings.add("tool " + t.name() + " of provider " + p.id() + " ignored: provider " + providerOf.get(t.name())
                            + " already has one");
                    continue;
                }
                tools.put(t.name(), t);
                providerOf.put(t.name(), p.id());
                any = true;
            }
            String text = p.instructions();
            if (any && text != null && !text.isBlank()) {
                instructions.add(text.strip());
            }
        }
    }

    public List<Tool> tools() {
        return List.copyOf(tools.values());
    }

    public String provider(String tool) {
        return providerOf.get(tool);
    }

    /** One paragraph per provider that contributed tools. */
    public List<String> instructions() {
        return List.copyOf(instructions);
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public ToolContext context() {
        return context;
    }

    public Result call(String name, Map<String, Object> arguments) {
        return call(name, arguments, Notifier.NONE);
    }

    /** Runs a tool; {@code notifier} receives its progress and log messages. */
    public Result call(String name, Map<String, Object> arguments, Notifier notifier) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return new Result(null, "Unknown tool " + name);
        }
        try {
            return new Result(tool.handler().call(new ToolArgs(arguments, notifier)), null);
        } catch (ToolException e) {
            return new Result(null, e.getMessage());
        } catch (NoSuchFileException e) {
            return new Result(null, "Does not exist: " + e.getFile() + (e.getReason() == null ? "" : " (" + e.getReason() + ")"));
        } catch (Exception | LinkageError e) {
            return new Result(null, name + " failed: " + e);
        }
    }
}
