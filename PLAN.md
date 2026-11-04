# mcp-set-tools (java-callgraph v2) — hybrid static + dynamic bug finder and MCP tool set

Goal: instrument Java programs (primarily WildFly under Arquillian) and combine the
runtime traces with static analysis of the bytecode to find bugs.

## Status

All phases are implemented on the `v2` branch, one commit per phase, plus an MCP server
(`mcp-set-tools-mcp`) over the analysis and live recording control, and developer tools
(`mcp-set-tools-devtools`: JUnit reports, Maven reactor/runs/logs, log queries, tables, thread
timelines), and sustaining tools (thread dumps, GC logs, heap histograms, JFR, method watches,
stack trace to version mapping, known issues with Jira search, bisect), and a daily status of pending
work across Jira, GitHub and GitLab. Not yet done:

* No automated test against a live WildFly/Arquillian setup; the integration tests use
  plain forked JVMs.
* The Maven plugin is verified by hand on a sample project, not by `maven-invoker-plugin`.
* The static detectors are intraprocedural (except lock order); interprocedural null and
  resource tracking would need method summaries.
* `maven_reactor` does not evaluate profile activation by JDK, OS, property or file (only
  named and `activeByDefault` profiles); `exact: true` delegates to Maven.
* Thread dumps: virtual threads (`jcmd Thread.dump_to_file`) are not parsed; only platform
  threads of `Thread.print`.
* `stacktrace_map` finds the enclosing method with a source heuristic (declarations above the
  line), not by compiling; Kotlin and Scala sources are not checked.
* `watch_method` samples hold argument references until the call ends: values are rendered at
  exit, so arguments mutated by the method show their final state.
* `daily_status` reads at most 100 items per source and 40 merge request details per GitLab
  instance; GitHub mentions are searched for the last 14 days.
* Log queries re-read the files on each call; JSON log lines (ECS, logstash) have no preset.
* `guard` queries cannot express "check() is called before act() in the same method";
  that needs an ordering analysis inside method bodies.

## Decisions

| Topic | Decision |
|-------|----------|
| Detectors | Generic first — no domain-specific (e.g. RBAC) checks yet |
| Bytecode library | ASM for both the static analyzer and the agent (replaces BCEL and Javassist) |
| Primary target | WildFly + Arquillian; standalone apps must still work |
| Home | This fork (`elguardian/java-callgraph`) |
| Java baseline | Build with `--release 17` (the oldest JDK current WildFly runs on); test on 17, 21, 25 |

## Current state (baseline)

* `stat/` — BCEL 6.2 bytecode walker printing `M:`/`C:` lines. No hierarchy resolution, no
  in-memory graph, no control/data flow. Lambda linking regex-parses `Code.toString()`.
* `dyn/` — Javassist agent writing `/tmp/calltrace.txt` + call-pair summary.
* `process_trace.rb` — Ruby 1.8 syntax, does not run on current Ruby.
* Tests: Cucumber, static analyzer only. On JDK 25, `lambda.feature` "Retrieve nested
  lambdas" fails (expected `lambda$methodA$2()` edge missing).

Known defects in the legacy agent (not patched — the legacy code is removed and replaced by
the ASM implementation; each defect becomes a regression test for v2):

1. `insertAfter` without `asFinally` → frames never popped on exceptional exit.
2. Call-pair summary is keyed by the frame string *including argument values*.
3. Invalid `incl`/`excl` regex adds `null` to the pattern list → NPE in `transform()`.
4. Argument `toString()` runs user code inside probes; single unbuffered `FileWriter`
   behind a global lock serializes all threads (masks races, distorts timing).
5. Argument values containing newlines break the trace format; output path hardcoded;
   probe `IOException`s propagate into the application.
6. `JCallGraph` continues after "Jar file does not exist" and aborts remaining jars.

## Architecture

```
              ┌────────────── mcp-set-tools-core ──────────────┐
              │ MethodId, ClassHierarchy, CallGraph,    │
              │ edge provenance (STATIC | DYNAMIC | BOTH)│
              └───────▲──────────────────────▲──────────┘
     mcp-set-tools-static ───┘                      └─── mcp-set-tools-agent (+ trace reader)
  (hierarchy, CHA/RTA, per-method               (enter / exit / throw, monitors,
   CFG + dataflow via ASM Analyzer)              optional field / alloc events)
              │                                    │
              └────────► mcp-set-tools-analyze ◄──────────┘
                     (merge, queries, detectors)
                               │
                          mcp-set-tools-cli ──► SARIF / text / JSON / DOT
```

Maven modules: `mcp-set-tools-rt` (probe runtime), `mcp-set-tools-core`, `mcp-set-tools-static`, `mcp-set-tools-agent`,
`mcp-set-tools-analyze`, `mcp-set-tools-cli`, plus `mcp-set-tools-testkit` (in-test compilation helpers).
The legacy BCEL/Javassist code and `process_trace.rb` are removed in Phase 0.

## WildFly / Arquillian constraints for the agent

* **Two jars.** `mcp-set-tools-agent.jar` (transformer + shaded/relocated ASM, on the system
  class path) and a tiny `mcp-set-tools-rt.jar` (probe runtime only, no dependencies) appended to
  the bootstrap class path via `appendToBootstrapClassLoaderSearch`. Only the runtime
  goes on bootstrap, so the LinkageError the README warns about (Javassist on two
  loaders) cannot happen.
* **Relocate ASM** (`io.github.elguardian.mcpsettools.shaded.asm`) — WildFly modules ship their own
  ASM/ByteBuddy copies.
* **One runtime package** (`io.github.elguardian.mcpsettools.rt`) so the JBoss Modules setting stays a
  single entry: `-Djboss.modules.system.pkgs=io.github.elguardian.mcpsettools.rt`.
* **Recording control.** The runtime registers an MBean (`mcp-set-tools:type=Recorder`:
  `start(label)`, `stop()`, `dump()`) so an Arquillian test can scope a trace to itself,
  either from inside the deployment or remotely over the management port. Default: record
  from startup (current behaviour).
* **Output** goes to `out=<dir>` (default `${java.io.tmpdir}/mcp-set-tools/<pid>`), one file
  per recording label, flushed on `stop()` and on JVM shutdown.
* Ship a documented `arquillian.xml` snippet and a Maven profile that passes the agent
  arguments via `server.jvm.args`.

## Phases

### Phase 0 — Foundation
* Split into the module layout above; delete the legacy sources, assembly descriptors,
  Cucumber tests and `process_trace.rb` (the Cucumber scenarios are ported to JUnit in
  Phase 1).
* `--release 17`, JUnit 5, CI matrix on 17/21/25.
* Test harness: compile fixture sources in-test (reuse `JARBuilder`), run the agent in a
  forked JVM, assert on parsed events.
* `mcp-set-tools-core` model: `MethodId` (owner, name, descriptor), `CallGraph` with provenance
  per edge, JSON serialization.

### Phase 1 — Static analyzer (ASM)
* Inputs: jars, class directories, classpath; app vs library scope.
* Class hierarchy; call graph with CHA (default) and RTA; `invokedynamic` resolved from
  bootstrap arguments (lambdas, method refs, string concat ignored).
* Per-method CFG + `Analyzer`/`Frame` dataflow available to detectors.
* Output: JSON, DOT, and the legacy `M:`/`C:` text format.

### Phase 2 — Agent (ASM)
* Events: method enter, normal exit, exceptional exit (try/catch wrapper that rethrows),
  `monitorenter`/`monitorexit`, `synchronized` methods, `java.util.concurrent.locks.Lock`
  calls (opt-in).
* Integer method IDs; ID table written once; per-thread buffers → compact binary file; no
  global lock on the hot path; probes never throw into the application.
* Argument capture opt-in: `args=none|types|toString` (default `none`).
* `premain` + `agentmain` (attach to a running server), `Can-Retransform-Classes`.
* Trace reader in Java (replaces `process_trace.rb`), including the timing report.

### Phase 3 — Merge & query
* Merge static and dynamic graphs; report runtime-only edges (reflection, proxies,
  EJB/CDI interceptors) and statically reachable but never-executed code.
* Queries: paths A→B, "does every path to B pass through C" (dominance), callers/callees
  within N hops.

### Phase 4 — Generic detectors (in order)
1. **Graph checks:** recursion cycles, unbounded runtime depth, dead/unreached code,
   static-vs-dynamic gaps.
2. **Exception safety:** swallowed exceptions (empty / log-only catch), exceptions escaping
   past cleanup, frames observed exiting exceptionally.
3. **Resource leaks:** `AutoCloseable` acquired without a close on some path; confirm
   with traces that executed the leaking path.
4. **Lock-order inversion:** lock-acquisition graph from monitor/lock events → cycles =
   potential deadlocks; static call graph widens to unobserved paths.
5. **Null dereference** (intraprocedural dataflow), prioritized by runtime reachability.

Each detector gets a severity and a confidence; a finding confirmed by a runtime trace ranks
above a static-only finding.

### Phase 5 — Reporting
* SARIF output (IDE / CI), each finding linked to its static path plus a sample trace.
* Optional Maven plugin wiring agent + Arquillian + analysis into `mvn verify`.

### Feature parity with the removed legacy tool
v2 covers every feature of the old README: incl/excl patterns, per-thread traces with depth
and nanosecond timestamps (as a text dump of the binary trace), the call-pair summary, the
timing report of `process_trace.rb`, the static `M:`/`C:` output, and the WildFly setup.
