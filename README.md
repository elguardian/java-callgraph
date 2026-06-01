java-callgraph: Java Call Graph Utilities
=========================================

A suite of programs for generating static and dynamic call graphs in Java.

* **javacg-static**: Reads classes from a jar file, walks down the method bodies and
  prints a table of caller-callee relationships.
* **javacg-dynamic**: Runs as a [Java agent](http://download.oracle.com/javase/6/docs/api/index.html?java/lang/instrument/package-summary.html)
  and instruments the methods of a user-defined set of classes in order to track their
  invocations at runtime. Produces a per-thread call trace with nanosecond timestamps and
  a call-pair summary at JVM exit.

## Build

Requires Maven. Run:

```
mvn install
```

This produces three jars under `target/`:

| Jar | Purpose |
|-----|---------|
| `javacg-0.1-SNAPSHOT.jar` | Standard Maven jar (library use) |
| `javacg-0.1-SNAPSHOT-static.jar` | Executable jar — static call graph generator |
| `javacg-0.1-SNAPSHOT-dycg-agent.jar` | Java agent — dynamic call graph generator |

---

## Static call graph

`javacg-static` accepts one or more jar files as arguments:

```
java -jar javacg-0.1-SNAPSHOT-static.jar lib1.jar lib2.jar ...
```

### Output format

**Method-level call:**
```
M:class1:<method1>(arg_types) (calltype)class2:<method2>(arg_types)
```

`method1` of `class1` called `method2` of `class2`. Call type codes:

| Code | Bytecode instruction |
|------|---------------------|
| `M`  | `invokevirtual` |
| `I`  | `invokeinterface` |
| `O`  | `invokespecial` |
| `S`  | `invokestatic` |
| `D`  | `invokedynamic` (argument types unavailable) |

**Class-level call:**
```
C:class1 class2
```

Some method in `class1` called some method in `class2`.

---

## Dynamic call graph (Java agent)

`javacg-dynamic` uses [Javassist](https://www.javassist.org/) to insert `push`/`pop` probes
at every method entry and exit point. It is **thread-aware**: each thread maintains its own
call stack, so multi-threaded applications (including application servers) produce complete,
non-interleaved per-thread traces.

### Agent argument format

```
-javaagent:javacg-0.1-SNAPSHOT-dycg-agent.jar=incl=pkg1.*,pkg2.*;excl=pkg1.internal.*
```

| Parameter | Description |
|-----------|-------------|
| `incl=<patterns>` | Comma-separated regex patterns (anchored at end). Classes matching any pattern are instrumented. |
| `excl=<patterns>` | Comma-separated regex patterns. Classes matching are skipped even if they match an `incl` pattern. |

Multiple `incl`/`excl` groups can be combined with `;` as delimiter.

### Output

**`/tmp/calltrace.txt`** — written at runtime, one line per method entry/exit:

```
>[depth][tid]fully.qualified.ClassName:methodName=timestamp_nanos   (entry)
<[depth][tid]fully.qualified.ClassName:methodName=timestamp_nanos   (exit)
```

**Standard output** — written at JVM shutdown, one line per unique caller→callee pair:

```
caller.Class:method callee.Class:method count
```

### Simple example

```bash
java \
  -javaagent:target/javacg-0.1-SNAPSHOT-dycg-agent.jar=incl=com.example.*; \
  -cp myapp.jar \
  com.example.Main
```

---

## JBoss / WildFly setup

WildFly uses [JBoss Modules](https://github.com/jboss-modules/jboss-modules), a custom
classloader that isolates subsystem modules from each other and from the JVM bootstrap
classloader. Without extra configuration the agent's `MethodStack` class is invisible to
instrumented JBoss module classes at runtime, causing `NoClassDefFoundError`.

Two things are required:

1. **Expose the agent package to JBoss Modules** via the system property
   `jboss.modules.system.pkgs`. JBoss Modules reads this property at startup and treats the
   listed packages as "system packages" — loadable from the bootstrap classloader by any
   module without an explicit dependency declaration.

2. **Attach the agent** with `incl`/`excl` patterns that target the subsystem packages you
   want to trace.

### Passing JVM arguments to the managed server

#### Option A — `standalone.conf` (permanent)

Edit `$WILDFLY_HOME/bin/standalone.conf` and append to `JAVA_OPTS`:

```bash
JAVA_OPTS="$JAVA_OPTS \
  -Djboss.modules.system.pkgs=org.jboss.byteman,gr.gousiosg.javacg.dyn \
  -javaagent:/path/to/javacg-0.1-SNAPSHOT-dycg-agent.jar=incl=org.jboss.as.jmx.*,org.jboss.as.controller.access.*;"
```

If `jboss.modules.system.pkgs` is already set (e.g. for Byteman), append with a comma:
```bash
-Djboss.modules.system.pkgs=org.jboss.byteman,gr.gousiosg.javacg.dyn
```

#### Option B — Arquillian test suite (`arquillian.xml`)

Pass the arguments as `javaVmArguments` in the container configuration:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<arquillian xmlns="http://jboss.org/schema/arquillian"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="http://jboss.org/schema/arquillian
                                http://jboss.org/schema/arquillian/arquillian_1_0.xsd">

    <defaultProtocol type="jmx-as7" />

    <container qualifier="jboss" default="true">
        <configuration>
            <property name="jbossHome">${jboss.install.dir}</property>
            <property name="javaVmArguments">
                ${server.jvm.args}
                -Djboss.modules.system.pkgs=gr.gousiosg.javacg.dyn
                -javaagent:/path/to/javacg-0.1-SNAPSHOT-dycg-agent.jar=incl=org.jboss.as.jmx.*,org.jboss.as.controller.access.*,org.jboss.as.domain.management.access.*,org.wildfly.mypackage.*;
            </property>
            <property name="serverConfig">${jboss.server.config.file.name:standalone.xml}</property>
            <property name="jbossArguments">${jboss.args}</property>
            <property name="allowConnectingToRunningServer">true</property>
            <property name="managementAddress">${node0:127.0.0.1}</property>
            <property name="managementPort">${as.managementPort:9990}</property>
            <property name="waitForPorts">${as.debug.port:8787} ${as.managementPort:9990}</property>
            <property name="waitForPortsTimeoutInSeconds">8</property>
            <property name="javaHome">${container.java.home}</property>
        </configuration>
    </container>

</arquillian>
```

### Choosing `incl` patterns

Instrument only the subsystem packages relevant to your analysis. Instrumenting too many
classes will slow the server significantly and produce very large `calltrace.txt` files.

| Goal | Suggested `incl` patterns |
|------|--------------------------|
| JMX subsystem | `org.jboss.as.jmx.*` |
| Management RBAC | `org.jboss.as.controller.access.*,org.jboss.as.domain.management.access.*` |
| Specific test deployment | `org.mycompany.mytests.*` |
| Full management layer | `org.jboss.as.controller.*` *(slow — use sparingly)* |

### Reading the trace

After the test run, `/tmp/calltrace.txt` contains one entry per method entry/exit.
To find the call chain for a specific operation, filter on a known entry point:

```bash
# Find the line numbers where callMBeanServer was entered/exited
grep -n "callMBeanServer" /tmp/calltrace.txt

# Show 200 lines of context around that entry point
sed -n '440530,440730p' /tmp/calltrace.txt

# Filter for RBAC/authorization calls within a line range
sed -n '440530,631287p' /tmp/calltrace.txt | \
  grep "StandardRBACAuthorizer\|ManagementSecurityIdentitySupplier\|AuthorizationResult"
```

The `[depth]` field in each line tells you the call stack depth at that point. A `>[N]` entry
followed by a `<[N]` exit with no deeper entries means the method made no instrumented calls.

### Classloader notes

WildFly module classloaders are isolated from each other. When Javassist instruments a class
loaded by a JBoss module classloader (e.g. `org.jboss.as.jmx`), the compiled probe code
(`MethodStack.push(...)`) must be resolvable at that classloader's level. The agent handles
this in two ways:

1. **Compile time** — `ClassPool.getDefault().insertClassPath(new ClassClassPath(MethodStack.class))`
   is called in `premain()`, making `MethodStack` visible to Javassist's compiler for every
   subsequent instrumentation.

2. **Runtime** — `jboss.modules.system.pkgs=gr.gousiosg.javacg.dyn` causes JBoss Modules to
   add the agent package to every module's classloader as a system package, so the
   `MethodStack` class found in the bootstrap classloader at JVM startup is the same class
   instance used at method invocation time.

Do **not** use `Instrumentation.appendToBootstrapClassLoaderSearch()` for the agent jar in a
WildFly environment. It causes Javassist's own classes to appear in both the bootstrap and
application classloaders, which triggers `LinkageError` on every call to
`CtClass.getDeclaredBehaviors()`, silently preventing all instrumentation.

---

## Known restrictions

* The static call graph generator does not account for methods invoked via reflection.
* The dynamic call graph generator does not handle exceptions that bypass normal returns;
  affected methods may appear as never having exited in `calltrace.txt`.
* Instrumenting a very large number of classes significantly increases startup time and
  memory usage due to Javassist's class transformation overhead.

---

## Author

Georgios Gousios <gousiosg@gmail.com>

## License

[2-clause BSD](http://www.opensource.org/licenses/bsd-license.php)
