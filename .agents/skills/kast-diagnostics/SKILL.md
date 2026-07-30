---
name: kast-diagnostics
description: Diagnose Kast readiness, indexing, timeouts, configuration, telemetry, profiling, logs, and read-only source-index topology when commands stall, evidence is incomplete, or enterprise-scale performance needs inspection.
---

# Kast Diagnostics

Diagnose the exact workspace from typed local evidence. Treat reported enterprise scale and timing as genuine; do not require a smaller reproduction.

## Establish the local control surface

Run from the canonical workspace root. Resolve the active release-local control CLI only into a shell variable; never print or persist its absolute path.

```shell
command -v jq >/dev/null
allowlist_json() {
  jq '
    def listed($values; $value): ($values | index($value)) != null;
    def safe_string($field; $value):
      if $field == "state"
      then listed(["STARTING", "INDEXING", "READY", "DEGRADED", "INCOMPLETE",
        "UNAVAILABLE", "CURRENT", "QUALIFIED", "COMPLETE", "LIMITED", "FAILED",
        "EXTERNAL_BOUNDARY"]; $value)
      elif $field == "qualification"
      then listed(["CURRENT", "QUALIFIED"]; $value)
      elif $field == "installAuthority"
      then listed(["active-release", "missing"]; $value)
      elif $field == "backendName"
      then listed(["idea", "headless"]; $value)
      elif $field == "detail"
      then listed(["basic", "verbose"]; $value)
      elif $field == "scopes"
      then ($value | split(",") | all(.[];
        listed(["all", "references", "call-hierarchy", "type-hierarchy",
          "implementations", "completions", "semantic-insertion-point",
          "diagnostics", "optimize-imports", "resolve", "workspace-files",
          "workspace-symbol-search", "workspace-search", "read-action",
          "file-outline", "apply-edits", "refresh"]; .)))
      elif $field == "modes"
      then ($value | split(",") | all(.[]; listed(["cpu", "alloc", "lock", "wall"]; .)))
      else false end;
    [paths(scalars) as $path
      | ($path[-1] | tostring) as $field
      | getpath($path) as $value
      | select(
          (($value | type) == "boolean"
            and listed(["ok", "ready", "healthy", "active", "indexing", "reachable",
              "referenceIndexReady", "installed", "enabled", "truncated"]; $field))
          or (($value | type) == "number"
            and (listed(["generation", "total", "indexed", "excluded", "pending",
              "limited", "failed", "stale", "parallelism", "batchSize",
              "modulePriorityDepth", "nodeCount", "edgeOccurrenceCount",
              "weightedEdgeCount", "componentCount", "stronglyConnectedComponentCount",
              "communityCount", "loadNanos", "computeNanos", "databaseBytes",
              "peakRssBytes", "queryP95Micros", "requestTimeoutMillis",
              "maxConcurrentRequests", "maxResults", "toolingApiTimeoutMillis",
              "identifierIndexWaitMillis", "debounceMillis", "durationSeconds",
              "occurrenceCount", "sourceFileCount", "sourceModuleCount",
              "targetSymbolCount", "targetFileCount", "targetModuleCount",
              "externalTargetCount", "referenceCount", "publicApiCount",
              "internalLeakCount", "indexCompleteness", "depth", "totalCount",
              "returnedCount", "nextOffset", "limit", "offset"]; $field)))
          or (($value | type) == "string" and safe_string($field; $value))
        )
      | {field: $field, value: $value}]
  ' 2>/dev/null
}
KASTCTL="${KAST_HOME:-$HOME/.local/share/kast}/current/libexec/kastctl"
test -x "$KASTCTL"
READY_JSON="$("$KASTCTL" --output json ready --workspace-root "$PWD" --for agent 2>/dev/null)"
STATUS_JSON="$("$KASTCTL" --output json status --workspace-root "$PWD" --backend idea 2>/dev/null)"
printf '%s\n' "$READY_JSON" "$STATUS_JSON" | allowlist_json
```

Inspect before starting or restarting anything. Distinguish runtime reachability, Gradle readiness, reference-index readiness, and persisted graph coverage. Never echo the captured JSON. Use `allowlist_json` in the same local shell invocation before producing user-visible output; if `jq` or safe filtering is unavailable, do not run the diagnostic.

## Inspect configuration and paths locally

```shell
CONFIG_JSON="$("$KASTCTL" --output json config list --workspace-root "$PWD" 2>/dev/null)"
PATHS_JSON="$("$KASTCTL" --output json developer inspect paths --workspace-root "$PWD" --idea 2>/dev/null)"
printf '%s\n' "$CONFIG_JSON" | allowlist_json
```

Use the first result to identify effective values and mutable fields. Use the second only to locate the current IDEA and Kast logs. Never echo either variable. Report only closed status names, booleans, allowlisted counts and durations, and the exact configuration keys named in this skill.

There is no typed log-reader command. Run searches only over the relevant time window, capture stdout into a variable, and discard stderr with `2>/dev/null`. Never echo the capture or report log-derived strings, including error codes; reduce it locally to counts and timings. Never return raw log lines, paths, project names, symbols, endpoints, tokens, process descriptors, sockets, or PIDs. Do not enable `KAST_IDEA_TRACE`; it records extensive host and workspace metadata.

## Change one supported knob at a time

Select only keys listed as mutable by `config list`. Record the inherited value locally, set one override, restart once, reproduce once, then unset the override and restart to restore inheritance.

Configuration and runtime mutations require explicit user authorization. For diagnose, explain, or inspect requests, stop after recommending the single evidence-backed key.

```shell
"$KASTCTL" config set <KEY> <VALUE> --workspace-root "$PWD"
"$KASTCTL" developer runtime restart \
  --workspace-root "$PWD" --backend idea --accept-indexing
"$KASTCTL" config unset <KEY> --workspace-root "$PWD"
```

Route symptoms to these current mutable keys:

- request saturation: `server.requestTimeoutMillis`, `server.maxConcurrentRequests`, `server.maxResults`
- Gradle or identifier waits: `gradle.toolingApiTimeoutMillis`, `indexing.identifierIndexWaitMillis`
- relationship indexing: `indexing.relationships.enabled`, `.batchSize`, `.parallelism`, `.modulePriorityDepth`
- project lifecycle: `projectOpen.gradleLoadEnabled`, `backends.idea.enabled`, `watcher.debounceMillis`
- telemetry: `telemetry.enabled`, `.scopes`, `.detail`
- profiling: `profiling.enabled`, `.modes`, `.durationSeconds`, `.emitManifest`

Prefer one narrow telemetry scope such as `references`, `type-hierarchy`, `workspace-files`, `workspace-symbol-search`, `read-action`, or `refresh`; use `basic` detail before `verbose`. Profiling modes are `cpu`, `alloc`, `lock`, and `wall`. Configuration acceptance does not prove a profile was recorded: the current runtime parses profiling settings but has no reliable profile-capture consumer. Say so instead of promising an artifact.

## Read the source index through typed commands

Use the public Rust graph surface for generation-checked, SQLite read-only, query-only projections:

```shell
GRAPH_SUMMARY="$(kast graph summary --scope symbol 2>/dev/null)"
MODULE_TOPOLOGY="$(kast graph topology --scope module 2>/dev/null)"
PACKAGE_COMMUNITIES="$(kast graph communities --scope package 2>/dev/null)"
```

Use bounded typed metrics when a focused query answers the diagnostic:

```shell
METRICS_JSON="$("$KASTCTL" --output json developer inspect metrics fan-in \
  --workspace-root "$PWD" --limit 50 2>/dev/null)"
printf '%s\n' "$METRICS_JSON" | allowlist_json
# Substitute fan-out, impact, coupling, or search only when that typed query is needed.
```

Never echo captured topology or metric payloads; they can contain repository names. Extract only the allowlisted numeric facts and closed state or qualification values before reporting. Never invoke `sqlite3`, expose `--database`, construct SQL, mutate the index, or copy the database. Prefer module or package projections over full symbol topology for large repositories.

## Report

Return the observed state transition, the typed command family with sensitive operands replaced by `<REDACTED>`, sanitized timing/count evidence, the single changed key and restoration status, and the next smallest action. Separate observed failures from static risks.
