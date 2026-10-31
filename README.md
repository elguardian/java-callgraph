java-callgraph
==============

Hybrid static + dynamic analysis for Java programs. The tool builds call graphs from
bytecode (static) and from an instrumenting Java agent (dynamic), merges them, and runs bug
detectors over the result. The primary target is WildFly under Arquillian; plain JVM
applications work the same way.

Version 2 is a rewrite on [ASM](https://asm.ow2.io/). The original BCEL/Javassist tool
(`javacg-static` / `javacg-dynamic`) has been removed; see [PLAN.md](PLAN.md) for the design
and the phase-by-phase roadmap.

## Modules

| Module | Purpose |
|--------|---------|
| `javacg-core` | Shared model: `MethodId`, `CallGraph` with per-edge provenance, JSON output |
| `javacg-static` | Static analysis: class hierarchy, CHA/RTA call graph, control/data flow engine |
| `javacg-cli` | Command line front end, packaged as `javacg-cli/target/javacg.jar` |
| `javacg-testkit` | Test helpers: in-memory compilation of fixtures, forked JVM runs |

## Build

Requires JDK 17 or newer and Maven 3.9+.

```
mvn verify
```

## Static call graph

```
java -jar javacg-cli/target/javacg.jar static [--lib deps.jar] [--algorithm cha|rta] \
     [--format text|json|dot|dot-classes] [--output file] app.jar|classes/|app.war ...
```

* Inputs: jars, class directories, `.class` files, WAR/EAR/RAR archives (nested
  `WEB-INF/lib` jars and EAR modules are read). Multi-release variants are ignored.
* `--lib` inputs complete the class hierarchy but their method bodies are not analyzed.
  For fat jars and big deployments, pass third-party code as `--lib`: application inputs
  keep every method body in memory.
* Classes missing from the inputs are loaded from the running JDK.
* `--algorithm cha` (default): a virtual/interface call reaches the implementation in every
  concrete subtype of the declared receiver type. `rta`: only application classes that are
  instantiated somewhere in application code count.
* Lambdas and method references are linked from the creating method to their implementation
  (call kind `D`); `invokedynamic` string concatenation is ignored.

`--format text` is the original output, one line per call instruction:

```
C:class1 class2                                          class1 references class2
M:class1:method1(arg_types) (type)class2:method2(arg_types)
```

| Type | Instruction |
|------|-------------|
| `M` | `invokevirtual` |
| `I` | `invokeinterface` |
| `O` | `invokespecial` |
| `S` | `invokestatic` |
| `D` | `invokedynamic` |

`json` lists methods and resolved edges (with call kinds, source lines and provenance);
`dot` / `dot-classes` render the graph for Graphviz.

## Credits

Originally written by Georgios Gousios <gousiosg@gmail.com>, with contributions from
Matthieu Vergne and others.

## License

[2-clause BSD](LICENSE)
