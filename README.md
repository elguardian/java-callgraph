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
| `javacg-testkit` | Test helpers: in-memory compilation of fixtures, forked JVM runs |

## Build

Requires JDK 17 or newer and Maven 3.9+.

```
mvn verify
```

## Credits

Originally written by Georgios Gousios <gousiosg@gmail.com>, with contributions from
Matthieu Vergne and others.

## License

[2-clause BSD](LICENSE)
