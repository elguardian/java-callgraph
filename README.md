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
| `javacg-core` | Shared model: `MethodId`, `CallGraph` with per-edge provenance, JSON/DOT output, trace reader |
| `javacg-rt` | Probe runtime called by instrumented code (bootstrap class path, no dependencies) |
| `javacg-agent` | Java agent, packaged as `javacg-agent/target/javacg-agent.jar` |
| `javacg-static` | Static analysis: class hierarchy, CHA/RTA call graph, control/data flow engine |
| `javacg-analyze` | Merged static + runtime model, graph queries, gap report, bug detectors |
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

## Dynamic call graph (Java agent)

```
java -javaagent:javacg-agent.jar='incl=com\.example\..*;out=/tmp/javacg' -cp app.jar com.example.Main
```

The agent instruments the selected classes with ASM. Every method records entry, normal
exit and exceptional exit (a catch-all handler that rethrows), so stacks stay balanced when
exceptions propagate. Events go to per-thread buffers and a compact binary file; there is no
global lock on the hot path, argument `toString()` is never called unless asked for, and a
probe failure (e.g. a full disk) disables recording instead of reaching the application.

### Options

`-javaagent:javacg-agent.jar=key=value;key=value...` (quote it in the shell: `;` separates
commands there).

| Option | Default | Meaning |
|--------|---------|---------|
| `incl=re,...` | — | Classes to instrument: Java regexes that must match the whole binary class name |
| `excl=re,...` | — | Classes to skip even if included |
| `out=dir` | `${java.io.tmpdir}/javacg/<pid>` | Base directory; each recording is a subdirectory |
| `label=name` | `default` | Name of the recording started at startup |
| `record=startup\|manual` | `startup` | `manual` records nothing until `Recorder.start(label)` |
| `args=none\|types\|tostring` | `none` | Argument capture: runtime classes, or `toString()` values (200 chars max each) |
| `locks=true\|false` | `false` | Record monitor, `synchronized` method and `java.util.concurrent` `Lock` acquire/release |
| `jmx=auto\|off\|<ms>` | `auto` | Delay before the `javacg:type=Recorder` MBean is registered; `auto` = 20 s on WildFly, else 0 |
| `verbose=true\|false` | `false` | Log every instrumented class |

`java.*`, `jdk.*`, `sun.*`, `com.sun.*`, `javax.management.*` and the agent's own classes are
never instrumented. Invalid patterns or options are reported on stderr and ignored.

The agent can also be loaded into a running JVM (`jcmd <pid> JVMTI.agent_load
/path/javacg-agent.jar '<options>'`, or the Attach API); already loaded classes that match
are retransformed. Methods that are executing at that moment keep running uninstrumented.

On startup the JVM may print *"Sharing is only supported for boot loader classes because
bootstrap classpath has been appended"*: the runtime jar is appended to the bootstrap class
path, which turns off class data sharing for the other loaders. It is harmless.

### Recording control

A recording is the directory `<out>/<label>/`. From code running in the traced JVM (for
example an Arquillian test running in the container):

```java
import gr.gousiosg.javacg.rt.Recorder;   // javacg-rt, scope provided: never package it

Recorder.start("MyTest#testLogin");      // completes the current recording, starts a new one
...
Recorder.stop();                         // writes the symbol and thread tables
```

From outside, use the MBean `javacg:type=Recorder` (operations `start(label)`, `stop()`,
`dump()`; attributes `Recording`, `Label`, `RecordingDirectory`, `OutputDirectory`). The
current recording is also completed by a shutdown hook.

### Reading a recording

```
java -jar javacg.jar trace [--format text|pairs|timing|json|dot] <out>/<label>
```

* `text` — the original `calltrace.txt` lines: `>[depth][tid]pkg.Class:method=nanos` on
  entry and `<[depth][tid]...` on exit. Captured arguments appear as `method [a, b]`,
  exceptional exits as `method!java.io.IOException`. Newlines and `=` inside values are
  escaped, so each event is one line.
* `pairs` — `caller callee count`, the original summary printed at JVM exit.
* `timing` — self time, total time, calls and maximum recursion depth per method (what
  `process_trace.rb` computed).
* `json` / `dot` — the runtime call graph.

The files in a recording are described in `javacg-rt/.../TraceFormat.java`; the
`symbols.tsv` table lists every instrumented method, including the ones that never ran.

## Merging static and runtime graphs

```
java -jar javacg.jar merge --app app.war --lib deps.jar --trace /tmp/javacg [--format gaps|json|dot|dot-classes]
```

`--trace` takes a recording directory or the agent's `out=` directory (every recording in it
is loaded). Every edge of the merged graph is `STATIC`, `DYNAMIC` (runtime only) or `BOTH`.
The default `gaps` report lists where the two views disagree:

* **runtime-only edges**: calls with no static call site, e.g. reflection, dynamic proxies,
  EJB/CDI interceptors, or callbacks made through code that was not instrumented;
* **static edges never exercised although the caller ran**: untested branches, or edges
  CHA over-approximated;
* **instrumented methods that never ran**.

## Queries

```
java -jar javacg.jar query paths   --app app.jar --from 'com.acme.Api.*' --to 'com.acme.Dao.delete'
java -jar javacg.jar query guard   --app app.jar --trace out/ --from 'com.acme.Api.*' \
                                   --to 'com.acme.Dao.delete' --through 'com.acme.Security.check*'
java -jar javacg.jar query callers --trace out/ --method 'com.acme.Dao.*' --max-depth 3
java -jar javacg.jar query callees --app app.jar --method 'com.acme.Api.login(java.lang.String)'
```

Method patterns are `pkg.Class.method` with `*` wildcards and an optional parameter suffix,
either Java types `(int,java.lang.String)` or a JVM descriptor `(ILjava/lang/String;)V`.
`--edges static|dynamic` restricts a query to what the static analysis found or to what
actually ran (default: all edges).

`guard` checks that every call chain from `--from` to `--to` passes through a `--through`
method, i.e. that the guard is a caller on the stack, and exits with code 3 printing a
counterexample otherwise, so it can fail a CI build. It works on the call graph, which has
no notion of order within a method body: a check made by the same method just *before* the
guarded call (`check(); delete();`) is a sibling, not a caller, and does not count.

## Bug detectors

```
java -jar javacg.jar analyze --app app.war --lib deps.jar --trace /tmp/javacg \
     [--detector resources --detector nulls ...] [--min-severity low] [--fail-on high]
```

Each finding has a severity (how bad if real) and a confidence (how likely it is real).
Findings backed by a recording are marked *confirmed at runtime* and listed first.
`--fail-on <severity>` exits with code 4 when such findings exist, for CI.

| Detector | Rule | Needs | What it reports |
|----------|------|-------|-----------------|
| `nulls` | `null.dereference` | static | a value that is always null is dereferenced |
| | `null.dereference-after-check` | static | a value compared with null is dereferenced on the path where it is null |
| | `null.dereference-possible` | static | a `null` assigned on some path reaches a dereference |
| `resources` | `resource.leak` | static | an `AutoCloseable` the method opened is still open at a `return` |
| | `resource.leak-on-exception` | static | it is not closed if a later call throws (no try/finally) |
| `exceptions` | `exception.swallowed` | static | a catch block neither reads nor rethrows the exception |
| | `exception.interrupt-ignored` | static | `InterruptedException` caught without restoring the interrupt or rethrowing |
| | `exception.cause-lost` | static | a new exception is thrown from a catch block without the caught one as cause |
| | `runtime.unexpected-exception` | runtime | an NPE, `ClassCastException`, index error, `ConcurrentModificationException`, `StackOverflowError`, ... left an application method |
| `locks` | `deadlock.lock-order` | runtime (`locks=true`) | two locks taken in opposite orders by code that ran |
| | `deadlock.static-lock-order` | static | lock types taken in opposite orders along static call paths |
| | `deadlock.nested-same-type` | static | two parameters of the same type locked one inside the other (`transfer(from, to)`) |
| `recursion` | `recursion.deep` | runtime | a method recursed 200+ levels deep |
| | `recursion.cycle` | static | mutual recursion (informational) |
| `dead-code` | `dead.private-method` | static | a private method nothing calls, statically or at runtime |

Runtime data strengthens static findings: a null dereference in a method that threw an NPE,
or a swallowed exception that was actually caught at runtime, gets high confidence and the
observation as evidence.

How the static detectors work, and their limits:

* `nulls`, `resources` and `exceptions` are intraprocedural data-flow analyses over the
  bytecode. Values coming from parameters, fields and method results are unknown, so only
  nulls and resources the method itself produces are reported.
* A resource is no longer the method's responsibility once it is returned, stored in a field
  or array, captured by a lambda or passed to another method. Decorators
  (`new BufferedReader(reader)`) are only tracked when they wrap a resource the same method
  opened, and closing either the decorator or the wrapped resource closes both.
* A catch variable named `ignored`/`ignore*`/`expected` marks a swallow as intentional, but
  javac drops the variable name of an empty catch block from the class file, so this only
  works when the block has a statement.
* The static lock analysis abstracts locks by type (`instance of C`, `field C.f`,
  `class C`); a reported cycle may involve different instances. Runtime lock-order findings
  are about real objects.

## WildFly / JBoss EAP

WildFly loads subsystems and deployments through JBoss Modules, whose class loaders only see
JDK packages and the packages listed in `jboss.modules.system.pkgs`. The probe runtime is on
the bootstrap class path, so its package must be listed there:

```bash
# $WILDFLY_HOME/bin/standalone.conf
JAVA_OPTS="$JAVA_OPTS -Djboss.modules.system.pkgs=org.jboss.byteman,gr.gousiosg.javacg.rt"
JAVA_OPTS="$JAVA_OPTS -javaagent:/path/to/javacg-agent.jar=incl=org\.jboss\.as\.jmx\..*,org\.jboss\.as\.controller\.access\..*;out=/tmp/javacg"
```

If `jboss.modules.system.pkgs` is already set (the default `standalone.conf` sets it from
`JBOSS_MODULES_SYSTEM_PKGS`, usually `org.jboss.byteman`), append
`,gr.gousiosg.javacg.rt` to the existing value rather than setting it twice.

With the default `jmx=auto` the agent waits 20 s before registering its MBean: touching the
platform MBean server before WildFly has installed its own MBean server builder and log
manager breaks the boot.

### Arquillian

Pass the same arguments through the container's `javaVmArguments`:

```xml
<container qualifier="jboss" default="true">
    <configuration>
        <property name="jbossHome">${jboss.install.dir}</property>
        <property name="javaVmArguments">
            ${server.jvm.args}
            -Djboss.modules.system.pkgs=org.jboss.byteman,gr.gousiosg.javacg.rt
            -javaagent:/path/to/javacg-agent.jar=incl=org\.jboss\.as\.jmx\..*,org\.wildfly\.mypackage\..*;out=${project.build.directory}/javacg;record=manual
        </property>
    </configuration>
</container>
```

With `record=manual`, each test can scope a recording to itself by calling `Recorder.start` /
`Recorder.stop` in the container (`@Before`/`@After`), or remotely through the MBean.

Instrument only the subsystem packages you need: every instrumented method adds a few
nanoseconds per call and a trace record.

| Goal | Suggested `incl` patterns |
|------|---------------------------|
| JMX subsystem | `org\.jboss\.as\.jmx\..*` |
| Management RBAC | `org\.jboss\.as\.controller\.access\..*,org\.jboss\.as\.domain\.management\.access\..*` |
| A test deployment | `org\.mycompany\.mytests\..*` |
| Whole management layer | `org\.jboss\.as\.controller\..*` (slow) |

## Credits

Originally written by Georgios Gousios <gousiosg@gmail.com>, with contributions from
Matthieu Vergne and others.

## License

[2-clause BSD](LICENSE)
