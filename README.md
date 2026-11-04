mcp-set-tools
=============

A set of tools for working on Java projects, served to AI assistants over the Model Context
Protocol: call-graph analysis, live JVM diagnostics, build and test results, logs, timelines,
known issues and a daily status of pending work. It grew out of
[java-callgraph](https://github.com/gousiosg/java-callgraph), whose call-graph analysis it still
contains.

Hybrid static + dynamic analysis for Java programs. The tool builds call graphs from
bytecode (static) and from an instrumenting Java agent (dynamic), merges them, and runs bug
detectors over the result. The primary target is WildFly under Arquillian; plain JVM
applications work the same way.

Version 2 is a rewrite on [ASM](https://asm.ow2.io/). The original BCEL/Javassist tool
(`javacg-static` / `javacg-dynamic` of java-callgraph) has been removed; see [PLAN.md](PLAN.md) for the design
and the phase-by-phase roadmap.

## Modules

| Module | Purpose |
|--------|---------|
| `mcp-set-tools-core` | Shared model: `MethodId`, `CallGraph` with per-edge provenance, JSON/DOT output, trace reader |
| `mcp-set-tools-rt` | Probe runtime called by instrumented code (bootstrap class path, no dependencies) |
| `mcp-set-tools-agent` | Java agent, packaged as `mcp-set-tools-agent/target/mcp-set-tools-agent.jar` |
| `mcp-set-tools-static` | Static analysis: class hierarchy, CHA/RTA call graph, control/data flow engine |
| `mcp-set-tools-analyze` | Merged static + runtime model, graph queries, gap report, bug detectors |
| `mcp-set-tools-cli` | Command line front end, packaged as `mcp-set-tools-cli/target/mcp-set-tools.jar` |
| `mcp-set-tools-maven-plugin` | `prepare-agent` and `analyze` goals for `mvn verify` |
| `mcp-set-tools-devtools` | Tables, stack traces, JUnit reports, Maven reactor/runs/logs, log parsing, thread timelines |
| `mcp-set-tools-mcp` | MCP server for AI assistants, packaged as `mcp-set-tools-mcp/target/mcp-set-tools-mcp.jar` |
| `mcp-set-tools-testkit` | Test helpers: in-memory compilation of fixtures, forked JVM runs |

## Build

Requires JDK 17 or newer and Maven 3.9+.

```
mvn verify
```

## Static call graph

```
java -jar mcp-set-tools-cli/target/mcp-set-tools.jar static [--lib deps.jar] [--algorithm cha|rta] \
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
java -javaagent:mcp-set-tools-agent.jar='incl=com\.example\..*;out=/tmp/mcp-set-tools' -cp app.jar com.example.Main
```

The agent instruments the selected classes with ASM. Every method records entry, normal
exit and exceptional exit (a catch-all handler that rethrows), so stacks stay balanced when
exceptions propagate. Events go to per-thread buffers and a compact binary file; there is no
global lock on the hot path, argument `toString()` is never called unless asked for, and a
probe failure (e.g. a full disk) disables recording instead of reaching the application.

### Options

`-javaagent:mcp-set-tools-agent.jar=key=value;key=value...` (quote it in the shell: `;` separates
commands there).

| Option | Default | Meaning |
|--------|---------|---------|
| `incl=re,...` | — | Classes to instrument: Java regexes that must match the whole binary class name |
| `excl=re,...` | — | Classes to skip even if included |
| `out=dir` | `${java.io.tmpdir}/mcp-set-tools/<pid>` | Base directory; each recording is a subdirectory |
| `label=name` | `default` | Name of the recording started at startup |
| `record=startup\|manual` | `startup` | `manual` records nothing until `Recorder.start(label)` |
| `args=none\|types\|tostring` | `none` | Argument capture: runtime classes, or `toString()` values (200 chars max each) |
| `locks=true\|false` | `false` | Record monitor, `synchronized` method and `java.util.concurrent` `Lock` acquire/release |
| `jmx=auto\|off\|<ms>` | `auto` | Delay before the `mcp-set-tools:type=Recorder` MBean is registered; `auto` = 20 s on WildFly, else 0 |
| `verbose=true\|false` | `false` | Log every instrumented class |

`out` and `label` may reference system properties as `%{name}` or `%{name:default}`. With
one argument line shared by every node of a cluster, `label=%{jboss.node.name:client}`
gives each JVM its own recording (`${...}` is avoided because Maven and Arquillian would
substitute it).

`java.*`, `jdk.*`, `sun.*`, `com.sun.*`, `javax.management.*` and the agent's own classes are
never instrumented. Invalid patterns or options are reported on stderr and ignored.

The agent can also be loaded into a running JVM (`jcmd <pid> JVMTI.agent_load
/path/mcp-set-tools-agent.jar '<options>'`, or the Attach API); already loaded classes that match
are retransformed. Methods that are executing at that moment keep running uninstrumented.

On startup the JVM may print *"Sharing is only supported for boot loader classes because
bootstrap classpath has been appended"*: the runtime jar is appended to the bootstrap class
path, which turns off class data sharing for the other loaders. It is harmless.

### Recording control

A recording is the directory `<out>/<label>/`. From code running in the traced JVM (for
example an Arquillian test running in the container):

```java
import io.github.elguardian.mcpsettools.rt.Recorder;   // mcp-set-tools-rt, scope provided: never package it

Recorder.start("MyTest#testLogin");      // completes the current recording, starts a new one
...
Recorder.stop();                         // writes the symbol and thread tables
```

From outside, use the MBean `mcp-set-tools:type=Recorder` (operations `start(label)`, `stop()`,
`dump()`; attributes `Recording`, `Label`, `RecordingDirectory`, `OutputDirectory`). The
current recording is also completed by a shutdown hook.

### Reading a recording

```
java -jar mcp-set-tools.jar trace [--format text|pairs|timing|json|dot] <out>/<label>
```

* `text` — the original `calltrace.txt` lines: `>[depth][tid]pkg.Class:method=nanos` on
  entry and `<[depth][tid]...` on exit. Captured arguments appear as `method [a, b]`,
  exceptional exits as `method!java.io.IOException`. Newlines and `=` inside values are
  escaped, so each event is one line.
* `pairs` — `caller callee count`, the original summary printed at JVM exit.
* `timing` — self time, total time, calls and maximum recursion depth per method (what
  `process_trace.rb` computed).
* `json` / `dot` — the runtime call graph.

The files in a recording are described in `mcp-set-tools-rt/.../TraceFormat.java`; the
`symbols.tsv` table lists every instrumented method, including the ones that never ran.

## Merging static and runtime graphs

```
java -jar mcp-set-tools.jar merge --app app.war --lib deps.jar --trace /tmp/mcp-set-tools [--format gaps|json|dot|dot-classes]
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
java -jar mcp-set-tools.jar query paths   --app app.jar --from 'com.acme.Api.*' --to 'com.acme.Dao.delete'
java -jar mcp-set-tools.jar query guard   --app app.jar --trace out/ --from 'com.acme.Api.*' \
                                   --to 'com.acme.Dao.delete' --through 'com.acme.Security.check*'
java -jar mcp-set-tools.jar query callers --trace out/ --method 'com.acme.Dao.*' --max-depth 3
java -jar mcp-set-tools.jar query callees --app app.jar --method 'com.acme.Api.login(java.lang.String)'
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
java -jar mcp-set-tools.jar analyze --app app.war --lib deps.jar --trace /tmp/mcp-set-tools \
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

`--sarif report.sarif --source-root src/main/java` additionally writes SARIF 2.1.0 (or use
`--format sarif`), which GitHub code scanning, IDEs and CI dashboards read. Each result has
the source file and line, the method as a logical location, related locations, mcp-set-tools's
severity/confidence and the runtime evidence, and a line-independent fingerprint.

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

## Maven plugin

`prepare-agent` puts the agent argument in the `mcpsettools.argLine` property; tests run under the
agent; `analyze` (bound to `verify`) analyzes `target/classes` with the runtime classpath as
libraries, loads the recordings, and writes `target/mcp-set-tools-report.txt` and
`target/mcp-set-tools-report.sarif`.

```xml
<plugin>
  <groupId>io.github.elguardian</groupId>
  <artifactId>mcp-set-tools-maven-plugin</artifactId>
  <version>2.0.0-SNAPSHOT</version>
  <configuration>
    <includes>
      <include>com\.acme\..*</include>
    </includes>
    <failOn>high</failOn>               <!-- optional: break the build -->
  </configuration>
  <executions>
    <execution>
      <goals>
        <goal>prepare-agent</goal>
        <goal>analyze</goal>
      </goals>
    </execution>
  </executions>
</plugin>
<plugin>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <argLine>${mcpsettools.argLine}</argLine>
  </configuration>
</plugin>
```

| `prepare-agent` parameter | Default | |
|---------------------------|---------|-|
| `includes` / `excludes` | — | class regexes (`incl=` / `excl=`) |
| `outputDirectory` | `target/mcp-set-tools` | recordings; cleaned first unless `clean=false` |
| `record`, `args`, `locks`, `jmx` | `startup`, `none`, `true`, `off` | agent options |
| `jbossModules` | `false` | also add `-Djboss.modules.system.pkgs=org.jboss.byteman,io.github.elguardian.mcpsettools.rt` |
| `propertyName` | `mcpsettools.argLine` | property receiving the JVM arguments (`mcpsettools.agentJar` holds the jar path) |

| `analyze` parameter | Default | |
|---------------------|---------|-|
| `classesDirectory` | `target/classes` | application classes |
| `useLibraries` | `true` | runtime classpath as `--lib` |
| `recordings` | `target/mcp-set-tools` | static analysis only if absent |
| `detectors` | all | detector ids |
| `minSeverity` / `failOn` | `low` / — | `-Dmcpsettools.failOn=high` on the command line works too |
| `sarifFile` / `textFile` | `target/mcp-set-tools-report.sarif` / `.txt` | |
| `sourceRoot` | first compile source root | SARIF path prefix |

All parameters can be skipped with `-Dmcpsettools.skip`.

## MCP server

`mcp-set-tools-mcp.jar` is a [Model Context Protocol](https://modelcontextprotocol.io) server on
stdin/stdout, so assistants such as Claude Code can analyze a program and drive recordings.
It keeps loaded models in memory: a deployment is analyzed once per session and then queried
many times, instead of once per CLI call.

```
claude mcp add mcp-set-tools -- java -jar /path/to/mcp-set-tools-mcp/target/mcp-set-tools-mcp.jar
```

Other clients take the same command in their JSON configuration:

```json
{ "mcpServers": { "mcp-set-tools": { "command": "java", "args": ["-jar", "/path/to/mcp-set-tools-mcp.jar"] } } }
```

| Tool | |
|------|-|
| `load_model` | static analysis of `app` inputs (`lib` for third-party jars) and/or `trace` recordings; returns a `model_id`. Loading the same inputs again refreshes that model |
| `list_models`, `unload_model` | models held by the server |
| `find_methods` | exact method names for a pattern, with static/runtime status |
| `describe_method` | runtime statistics and direct callers/callees with provenance, lines and counts |
| `call_paths`, `check_guard`, `callers`, `callees` | the `query` commands |
| `gap_report` | static vs runtime disagreements |
| `hotspots` | methods by self time, total time, calls or exceptional exits |
| `run_detectors`, `list_detectors` | the bug detectors, optionally writing SARIF |
| `export_graph` | the merged graph, or the part reachable from/to a pattern, as JSON or DOT |
| `list_jvms` | local JVMs and whether the agent is loaded in them |
| `attach_agent` | loads the agent into a running JVM (Attach API); the agent jar is embedded in the server |
| `recorder` | `status`, `start`, `stop`, `dump` through the `mcp-set-tools:type=Recorder` MBean, by `pid` or `jmx_url` |

Method names in results use the Java form (`a.b.C.m(int,java.lang.String)`) and can be passed
back as patterns; a pattern without a package that matches nothing is retried as `*.<pattern>`.
A live session looks like: `list_jvms` → `attach_agent` with `incl=...;record=manual` →
`recorder start` → exercise the application → `recorder stop` → `load_model` with the
returned directory as `trace` and the application as `app`.

* The agent can be loaded once per JVM; a second `attach_agent` (or `-javaagent` plus attach)
  is refused, since a second transformer would record every call twice.
* JDK 21+ prints a warning in the target when an agent is loaded dynamically; start it with
  `-XX:+EnableDynamicAgentLoading` to silence it. WildFly also needs `io.github.elguardian.mcpsettools.rt` in
  `jboss.modules.system.pkgs` from server start (see below), so for WildFly prefer
  `-javaagent` with `record=manual` and use `recorder` only.
* `recorder` by `pid` starts the target's local JMX agent if needed. `jmx_url` takes an RMI
  service URL or `host:port`; WildFly's `remote+http` protocol needs `jboss-client.jar`, which
  the server does not bundle.
* Recording directories are on the target's file system; `load_model` must see the same paths.
* Logging goes to stderr (`-Dorg.slf4j.simpleLogger.defaultLogLevel=debug` for more).

### Developer tools

The same server carries tools for everyday work on Java projects. They need no `load_model`;
each call reads its files again. Tools that return lists accept `table_format`
(`ascii`, `markdown`, `jira`, `slack`, `csv`, `tsv`, `html`) and then also return the list as
a table ready to paste.

| Tool | |
|------|-|
| `junit_summary` | totals per module or class, slowest tests, failures with exception and first application frame, failures grouped by cause, flaky tests, from surefire/failsafe/Gradle XML reports |
| `junit_failures` | failed tests with trimmed (or full) stack traces, signatures, and the end of their stdout/stderr |
| `junit_compare` | two runs: newly failing, fixed, still failing, added, removed, slower |
| `maven_reactor` | modules, coordinates, inter-module dependencies and build order from the poms; `-pl/-am/-amd` selections; the modules a set of changed files affects and the `-pl` to build them |
| `maven_run` | runs `mvnw`/`mvn` (batch, no transfer progress, timestamps and builder threads on each line) and returns module status and time, compiler errors with file:line:column, failed tests, goal failures and the `-rf` resume argument; the full log goes to a file |
| `maven_log` | the same analysis for an existing log or pasted output |
| `log_query` | records, counts, groups (by level, logger, thread or message template), error groups by stack trace signature, or a histogram, with level/thread/logger/message/time filters; multi-line records and stack traces stay together; plain or `.gz` files |
| `log_pattern` | the presets, the regex a layout compiles to, and how the first records of a file parse |
| `stacktrace_parse` | exception, cause chain, root cause, first application frame, signature and a trimmed trace |
| `table_render` | rows or CSV as ascii, markdown, jira, slack (code block), csv, tsv or html |
| `timeline` | what each thread did over time, from a recording, logs, a `maven_run` log or given events: busy time per thread, most threads busy at once, the same block running on several threads together, lock waits; drawn as ascii, mermaid, plantuml, chrome (Perfetto) or html |

Log formats are a preset (`wildfly`, `wildfly-console`, `logback`, `logback-full`, `log4j2`,
`spring-boot`, `maven`, `iso`), `auto`, a layout pattern as configured in the logging framework
(`%d{yyyy-MM-dd HH:mm:ss,SSS} %-5p [%c] (%t) %s%e%n`), or `regex:` with the named groups `ts`,
`level`, `thread`, `logger` and `message`. Lines that do not start a record belong to the one
before (stack traces, multi-line messages).

Timeline spans from logs are rules pairing a start and an end message, on the same thread or
across threads through a named group:

```json
{ "source": "log", "files": ["server.log"],
  "spans": [ { "name": "request", "start": "begin request (?<id>\\w+)", "end": "end request (?<id>\\w+)", "key": "id" },
             { "name": "query", "start": "query start", "end": "query end" } ],
  "markers": [ { "name": "error", "match": "Exception" } ] }
```

From a recording made with `locks=true`, a thread waiting for a monitor another thread holds
shows as `~` next to the holder's `=`:

```
worker-a AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
  .1     EEEEEEEEEEEEEEE============================
  .2     BBBBBBBBBBBBBBBDDDDDDDDDDDDDDDDDDDDDDDDDDDD
  .3                    BBBBBBBBBBBBBBBBBBBBBBBBBBBB
worker-b AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
  .1     EEEEEEEEEEEEEEE~~~~~~~~~~~~~~~~~~~~~~~~~~~~=============================
```

The agent records a lock before acquiring it, so a wait lasts until the thread's next event: it
is an upper bound. `maven_reactor` evaluates only the profiles it is given and `activeByDefault`
ones; `exact: true` asks `mvn validate` instead. `maven_run` executes the project's build:
plugins and tests run with the server's permissions.

### Sustaining tools

For escalations and regressions: reading what a customer sends, tying a failure to a version
and a known issue, finding the commit that broke something, and probing a live JVM.

| Tool | |
|------|-|
| `thread_dump_analyze` | jstack / `jcmd Thread.print` / `kill -3` dumps (JDK 8 to 25, several per file, inside logs), or taken from a pid: deadlocks over monitors and `java.util.concurrent` locks, each contended lock with its owner and what the owner is doing, threads grouped by state and top frames, pools, threads stuck across dumps, CPU used between dumps |
| `gc_log_analyze` | unified (`-Xlog:gc*`) and JDK 8 GC logs: pause percentiles per kind, paused share overall and in the worst window, allocation rate, heap after full GC over time with a leak flag, full GC causes, to-space exhaustion and allocation stalls |
| `heap_histogram` | `jcmd GC.class_histogram` / `jmap -histo` snapshots, from files or a pid: growth per class and classes growing in every snapshot |
| `jfr_analyze` | a JFR file, or a pid recorded through `jcmd JFR.start`: hot methods and stacks, monitor contention and parking, allocation by class and site, socket and file I/O, exceptions, GC, CPU load |
| `watch_method` | instruments one method of a running JVM for a while (loading the agent if needed) and reports calls, failures, p50/p90/p99, exceptions, and the first and slowest calls with arguments and results; the original bytecode is restored afterwards |
| `stacktrace_map` | maps each frame of a trace to the source at a git ref with the surrounding lines, checks that the line is in the method the frame names, and ranks versions or tags by how many frames fit |
| `known_issue_match` | matches traces (pasted, the error groups of a log, JUnit failures) against a known-issue store and a live Jira search; `known_issue_add` and `known_issue_import` (Jira CSV export) fill the store |
| `bisect_run` | `git bisect` in a temporary worktree with a Maven oracle (a test that fails, a log pattern, the build) or any command; runs as a background job |
| `job_status`, `job_list`, `job_cancel` | background jobs (`bisect_run`, and `watch_method` / `jfr_analyze` with `background`) |

Live-JVM tools (`pid`) use the `jcmd` of the JDK the server runs on and need the same user as
the target. `heap_histogram` by pid counts live objects, which forces a full GC in the target
(`all_objects` avoids it). `watch_method` shows values with `toString()`, which runs the
target's own code; `capture: types` avoids that. JDK and mcp-set-tools classes cannot be watched.

A trace from a customer, checked against releases:

```json
{ "text": "java.lang.IllegalStateException: ...\n\tat org.acme.Svc.save(Svc.java:88)\n...",
  "repo": ".", "tag_pattern": "7.4.*" }
```

returns each tag with the number of frames whose line falls in the named method, the best one,
and the source around every frame at that tag.

Known issues live in a JSON-lines file (`$MCPSETTOOLS_ISSUES`, default `~/.mcp-set-tools/known-issues.jsonl`)
that a team can keep in a repository. With `JIRA_URL` and `JIRA_TOKEN` (plus `JIRA_USER` for
Jira Cloud) in the server's environment, `known_issue_match` also searches Jira for the root
exception and the first application frame, and scores the hits by the stack traces in their
description and comments. The token is read from the environment only.

`bisect_run` with a Maven oracle:

```json
{ "project_dir": ".", "good": "7.4.10", "bad": "HEAD", "args": ["-pl", "core", "-am", "test",
  "-Dtest=OrderTest", "-Dsurefire.failIfNoSpecifiedTests=false"], "fails_test": "OrderTest#total" }
```

Commits that do not compile, or whose build fails before the tests run, are skipped. Use `test`
or `verify`, not `install`, so the bisect builds do not replace artifacts in the local Maven
repository.

### Daily status

`daily_status` gathers your pending work for the day: open Jira issues assigned to you, and
open GitHub pull requests and GitLab merge requests that you wrote, where your review is
requested, that are assigned to you or that mention you. It returns a markdown digest in three
sections, plus the items as data:

* **Needs you**, ordered by score:
  * a review requested from you and not done since the last push;
  * changes requested on your PR;
  * new comments or reviews from others since your last action (bots ignored);
  * failing CI or merge conflicts on your PR;
  * an unanswered mention;
  * your PR approved and green, ready to merge;
  * Jira issues that are overdue, due within two days, Blocker or Critical, or have new comments.
* **Waiting on others**: your PRs/MRs with nothing for you to do.
* **In progress**: the remaining Jira issues.

Items that changed since the previous run are marked **NEW**. The last run is kept in
`~/.mcp-set-tools/status-state.json` next to the config.

Instances are listed in `~/.mcp-set-tools/status.json` (or `$MCPSETTOOLS_STATUS_CONFIG`). Tokens are named
by environment variable and set in the MCP server's environment, never in the file:

```json
{
  "jira":   [ { "url": "https://issues.redhat.com", "token_env": "JIRA_TOKEN" } ],
  "github": [ { "api_url": "https://api.github.com", "token_env": "GITHUB_TOKEN" } ],
  "gitlab": [ { "url": "https://gitlab.com", "token_env": "GITLAB_TOKEN" },
              { "url": "https://gitlab.example.com", "token_env": "GITLAB_WORK_TOKEN", "name": "work",
                "ignore_repos": ["sandbox/*"] } ]
}
```

```
claude mcp add mcp-set-tools -e JIRA_TOKEN=... -e GITHUB_TOKEN=... -e GITLAB_TOKEN=... -e GITLAB_WORK_TOKEN=... \
    -- java -jar /path/to/mcp-set-tools-mcp.jar
```

The entries are:

* **Jira:** `url`, `token_env`, and optionally `user_env` (Jira Cloud: email plus API token,
  Basic auth) and `jql` (default: assigned to you and unresolved). A Data Center personal access
  token is sent as Bearer.
* **GitHub:** `api_url` (`https://api.github.com`, or `https://host/api/v3` for Enterprise) and a
  token that can read the repositories. One GraphQL query covers all four searches.
* **GitLab:** `url`, and a token with `read_api`. It uses merge request lists, todos (mentions),
  and each MR's pipeline, approvals and discussions.

Without a file, `JIRA_URL`/`JIRA_TOKEN`[/`JIRA_USER`], `GITHUB_TOKEN` (or `GH_TOKEN`) and
`GITLAB_URL`/`GITLAB_TOKEN` are used. A source that fails (expired token, unreachable) is
reported in the digest; the others still show.

## WildFly / JBoss EAP

WildFly loads subsystems and deployments through JBoss Modules, whose class loaders only see
JDK packages and the packages listed in `jboss.modules.system.pkgs`. The probe runtime is on
the bootstrap class path, so its package must be listed there:

```bash
# $WILDFLY_HOME/bin/standalone.conf
JAVA_OPTS="$JAVA_OPTS -Djboss.modules.system.pkgs=org.jboss.byteman,io.github.elguardian.mcpsettools.rt"
JAVA_OPTS="$JAVA_OPTS -javaagent:/path/to/mcp-set-tools-agent.jar=incl=org\.jboss\.as\.jmx\..*,org\.jboss\.as\.controller\.access\..*;out=/tmp/mcp-set-tools"
```

If `jboss.modules.system.pkgs` is already set (the default `standalone.conf` sets it from
`JBOSS_MODULES_SYSTEM_PKGS`, usually `org.jboss.byteman`), append
`,io.github.elguardian.mcpsettools.rt` to the existing value rather than setting it twice.

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
            -Djboss.modules.system.pkgs=org.jboss.byteman,io.github.elguardian.mcpsettools.rt
            -javaagent:/path/to/mcp-set-tools-agent.jar=incl=org\.jboss\.as\.jmx\..*,org\.wildfly\.mypackage\..*;out=${project.build.directory}/mcp-set-tools;record=manual
        </property>
    </configuration>
</container>
```

With `record=manual`, each test can scope a recording to itself by calling `Recorder.start` /
`Recorder.stop` in the container (`@Before`/`@After`), or remotely through the MBean.

With the Maven plugin, let `prepare-agent` build the arguments (`<jbossModules>true</jbossModules>`,
`<jmx>auto</jmx>`) and hand them to the container through a system property, which
`arquillian.xml` can reference:

```xml
<!-- pom.xml -->
<plugin>
  <artifactId>maven-failsafe-plugin</artifactId>
  <configuration>
    <systemPropertyVariables>
      <mcpsettools.argLine>${mcpsettools.argLine}</mcpsettools.argLine>
    </systemPropertyVariables>
  </configuration>
</plugin>

<!-- arquillian.xml -->
<property name="javaVmArguments">${server.jvm.args} ${mcpsettools.argLine}</property>
```

`analyze` then runs in `verify`, after the Arquillian tests. The WildFly setup is documented
from WildFly's class-loading rules but has not been run against a live server as part of
this project's test suite.

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
