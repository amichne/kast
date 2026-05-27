# Profiling the kast standalone daemon

This guide covers CPU flame graphs, allocation profiling, lock contention
analysis, and native memory tracking for the headless kast JVM daemon.

## Large-repository Docker harness

Use the repo-local harness when you want repeatable evidence from a clean Linux
container with Java 21, async-profiler, telemetry, JVM diagnostics, and a
recorded JSON-RPC workload.

```bash
scripts/profile-standalone-large-repo.sh \
  --target ktor \
  --duration 60 \
  --profile-modes wall,cpu \
  --gradle-jvmargs "-Xmx4g -XX:MaxMetaspaceSize=1g -XX:+UseParallelGC -Dfile.encoding=UTF-8"
```

The default public target is `ktorio/ktor` tag `3.2.3`, which is above 85%
Kotlin by GitHub Linguist and uses Gradle `8.14.2`. A local checkout check for
this tag found 1,995 Kotlin source files, zero Java source files, and 124 Gradle
projects, giving the standalone backend a real Kotlin-heavy module graph for
live semantic-workflow profiling. `opensearch-project/OpenSearch` tag `3.3.0`
remains available as `--target opensearch` when you specifically want a Gradle
`8.14.3` Java-heavy comparison target.

To stress exact Gradle `8.14.3` plus hundreds of generated Kotlin modules, run
the synthetic target:

```bash
scripts/profile-standalone-large-repo.sh \
  --target synthetic-kotlin \
  --modules 240 \
  --duration 60 \
  --profile-modes wall,cpu,alloc
```

Results are written under `.benchmarks/standalone-profile/results/<run-id>/`:

- `summary.json` records workspace counts, time to `health`, time to `READY`,
  sampled Kotlin files, and artifact locations.
- `rpc-latencies.jsonl` records every JSON-RPC call and latency.
- `telemetry/standalone-spans.jsonl` contains kast spans for discovery, indexing,
  workspace queries, lock, I/O, and memory scopes.
- `profiling/*.html` contains async-profiler flame graphs.
- `diagnostics/` contains `jcmd` native-memory, heap, thread, and system-property
  snapshots.
- `gradle.properties` records the harness-owned Gradle and Kotlin daemon JVM
  settings injected into the container's mounted Gradle home.

For memory-constrained Docker or Colima runs, prefer lowering the target Gradle
daemon heap with `--gradle-jvmargs` and `--kotlin-daemon-jvmargs` before raising
the standalone heap. When the local Docker runtime has enough memory assigned,
`--docker-memory 12g` can also make the container limit explicit.

## Locating the daemon PID

The daemon PID can be found via `jps` or from the workspace descriptor file
that kast writes on startup.

```bash
jps | grep -i kast
# or
cat "$TMPDIR"/kast-*.sock.desc 2>/dev/null | grep pid
```

## async-profiler

[async-profiler](https://github.com/async-profiler/async-profiler) (`asprof`)
produces low-overhead flame graphs directly from a running JVM.

### CPU profile

```bash
asprof -d 30 -f cpu.html <pid>
```

Captures 30 seconds of CPU samples and writes an interactive HTML flame graph.

### Allocation profile

```bash
asprof -d 30 -e alloc -f alloc.html <pid>
```

Captures object allocation sites. Useful for finding transient garbage or
unexpectedly large allocations during indexing.

### Lock contention profile

```bash
asprof -d 30 -e lock -f lock.html <pid>
```

Shows where threads spend time waiting for monitors and
`ReentrantReadWriteLock` acquisitions. Compare with the `kast.lock.*` telemetry
spans for a second perspective.

### Wall-clock profile (I/O-bound code)

```bash
asprof -d 30 -e wall -f wall.html <pid>
```

Samples all threads regardless of CPU state. This is the right mode for
diagnosing I/O-bound paths such as Gradle tooling API calls, SQLite writes,
or VFS file loading.

## JVM flags for better profiling

Add these flags to the daemon startup command (e.g., via `JAVA_OPTS` or
directly in the kast launcher script):

```
-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints
```

`DebugNonSafepoints` gives async-profiler accurate frame pointers even inside
compiled code that the JIT would normally elide.

### Native memory tracking

```
-XX:NativeMemoryTracking=summary
```

Once enabled, query the JVM's native memory breakdown at any time:

```bash
jcmd <pid> VM.native_memory summary
```

This reports class metadata, thread stacks, code cache, and GC overhead
separately from the managed heap.

## Thread naming conventions

The standalone backend names its threads with recognisable prefixes:

| Prefix                              | Owner                        |
|-------------------------------------|------------------------------|
| `kast-parallel-<N>`                | `ForkJoinPool` in `StandaloneAnalysisBackend` (line 119) |
| `kast-background-indexer-phase1`   | Phase 1 identifier index thread |
| `kast-background-indexer-phase2`   | Phase 2 symbol reference thread |

These names appear in flame graphs, thread dumps (`jstack <pid>`), and the
`kast.lock.caller` telemetry attribute, making it straightforward to attribute
contention to a specific subsystem.

## Built-in telemetry

When `telemetry.enabled = true` in `~/.config/kast/config.toml`, the daemon
writes JSONL spans to `~/.config/kast/telemetry/standalone-spans.jsonl`.

Analyze the output with the bundled script:

```bash
python scripts/analyze-spans.py ~/.config/kast/telemetry/standalone-spans.jsonl
```

For standalone profile result directories, use the operation summary helper to
combine startup timing, JSON-RPC latency, and telemetry spans into one JSON
report:

```bash
python scripts/profiling/summarize-profile-operations.py \
  .benchmarks/standalone-profile/results/<run-id>
```

The `spans.workspaceDiscovery` section is the first place to inspect fresh
Tooling API startup. `kast.workspaceDiscovery.toolingApiConnect` isolates the
connector open, while `kast.workspaceDiscovery.sourceSetTask` isolates the
Gradle-owned source-set extraction task.

### OTLP export to Jaeger / Zipkin

Set `KAST_OTLP_ENDPOINT` to send spans to an OTLP-compatible collector in
addition to the JSONL file:

```bash
# Start Jaeger all-in-one:
docker run -d -p 16686:16686 -p 4317:4317 jaegertracing/all-in-one

# Launch kast with OTLP export for the current workspace:
KAST_OTLP_ENDPOINT=http://localhost:4317 \
  kast daemon start --workspace-root="$PWD"
```

Open the [local Jaeger UI](http://localhost:16686) to browse traces with
parent-child span relationships.

## Combining approaches

A typical profiling session:

1. Enable telemetry scopes `memory,io,lock,indexing` in `config.toml`.
2. Start the daemon with `DebugNonSafepoints` and `NativeMemoryTracking`.
3. Trigger the workload (e.g., `kast rpc` with `raw/references`).
4. Collect a 30-second CPU flame graph via async-profiler.
5. Run `scripts/analyze-spans.py` for the telemetry view.
6. Cross-reference lock contention spans with the `lock` flame graph.
