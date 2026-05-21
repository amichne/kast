# Profiling the kast standalone daemon

This guide covers CPU flame graphs, allocation profiling, lock contention
analysis, and native memory tracking for the headless kast JVM daemon.

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

Open `http://localhost:16686` to browse traces with parent-child span
relationships.

## Combining approaches

A typical profiling session:

1. Enable telemetry scopes `memory,io,lock,indexing` in `config.toml`.
2. Start the daemon with `DebugNonSafepoints` and `NativeMemoryTracking`.
3. Trigger the workload (e.g., `kast rpc` with `raw/references`).
4. Collect a 30-second CPU flame graph via async-profiler.
5. Run `scripts/analyze-spans.py` for the telemetry view.
6. Cross-reference lock contention spans with the `lock` flame graph.
