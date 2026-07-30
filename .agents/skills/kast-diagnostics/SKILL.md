---
name: kast-diagnostics
description: Diagnose Kast readiness, indexing, timeouts, configuration, telemetry, profiling, logs, and read-only source-index topology when commands stall, evidence is incomplete, or enterprise-scale performance needs inspection.
---

# Kast Diagnostics

Diagnose the exact workspace from typed local evidence. Treat reported enterprise scale and timing as genuine; do not require a smaller reproduction.

## Establish the local control surface

Run from the canonical workspace root. Resolve the active release-local control CLI only into a shell variable; never print or persist its absolute path.

```shell
command -v jq >/dev/null || exit 1
allowlist_json() {
  jq '
    def listed($values; $value): ($values | index($value)) != null;
    def config_keys: [
      "server.requestTimeoutMillis", "server.maxConcurrentRequests", "server.maxResults",
      "gradle.toolingApiTimeoutMillis", "indexing.identifierIndexWaitMillis",
      "indexing.relationships.enabled", "indexing.relationships.batchSize",
      "indexing.relationships.parallelism", "indexing.relationships.modulePriorityDepth",
      "projectOpen.gradleLoadEnabled", "backends.idea.enabled", "backends.headless.enabled",
      "watcher.debounceMillis",
      "telemetry.enabled", "telemetry.scopes", "telemetry.detail", "profiling.enabled",
      "profiling.modes", "profiling.durationSeconds", "profiling.emitManifest"
    ];
    def config_path($path):
      ($path | map(tostring) | join(".")) as $dotted
      | if ($dotted | startswith("effective."))
        then ($dotted | ltrimstr("effective.")) as $key
          | if listed(config_keys; $key) then $key else null end
        else null end;
    def safe_string($field; $value):
      if $field == "state"
      then listed(["STARTING", "INDEXING", "READY", "DEGRADED", "INCOMPLETE",
        "UNAVAILABLE", "CURRENT", "QUALIFIED", "COMPLETE", "LIMITED", "FAILED",
        "EXTERNAL_BOUNDARY", "managed", "missing"]; $value)
      elif $field == "qualification"
      then listed(["CURRENT", "QUALIFIED"]; $value)
      elif $field == "installAuthority"
      then listed(["active-release", "missing"]; $value)
      elif ($field == "backendName" or $field == "kind")
      then listed(["idea", "headless"]; $value)
      elif $field == "scope"
      then listed(["SYMBOL", "PACKAGE", "MODULE"]; $value)
      elif $field == "detail"
      then listed(["basic", "verbose"]; $value)
      elif $field == "scopes"
      then ($value | split(",") | all(.[];
        listed(["all", "rename", "references", "call-hierarchy", "type-hierarchy",
          "implementations", "completions", "semantic-insertion-point",
          "diagnostics", "optimize-imports", "resolve", "workspace-files",
          "workspace-symbol-search", "workspace-search", "read-action",
          "file-outline", "apply-edits", "refresh"]; .)))
      elif $field == "modes"
      then ($value | split(",") | all(.[]; listed(["cpu", "alloc", "lock", "wall"]; .)))
      elif $field == "key"
      then listed(config_keys; $value)
      else false end;
    [paths((type == "string") or (type == "number") or (type == "boolean")) as $path
      | ($path[-1] | tostring) as $field
      | getpath($path) as $value
      | config_path($path) as $config_path
      | select(
          (($value | type) == "boolean"
            and listed(["ok", "ready", "healthy", "active", "indexing", "reachable",
              "referenceIndexReady", "installed", "enabled", "truncated",
              "gradleLoadEnabled", "emitManifest"]; $field))
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
      | {field: (if $field == "key" then "mutableKey"
          elif $config_path != null then $config_path else $field end), value: $value}]
  ' 2>/dev/null
}
KASTCTL="${KAST_HOME:-$HOME/.local/share/kast}/current/libexec/kastctl"
test -x "$KASTCTL" || exit 1
READY_JSON="$("$KASTCTL" --output json ready --workspace-root "$PWD" --for agent 2>/dev/null)" || :
test -n "$READY_JSON" || exit 1
printf '%s\n' "$READY_JSON" | allowlist_json || exit 1
BACKEND="$(printf '%s\n' "$READY_JSON" | jq -er '
  select(.agentEnvironment.ok == true)
  | .agentEnvironment.backend
  | select(.state == "managed")
  | .kind
  | select(. == "idea" or . == "headless")
' 2>/dev/null)" || BACKEND=
if [ -n "$BACKEND" ]; then
  STATUS_JSON="$("$KASTCTL" --output json status --workspace-root "$PWD" \
    --backend "$BACKEND" 2>/dev/null)" || :
  test -n "$STATUS_JSON" || exit 1
  printf '%s\n' "$STATUS_JSON" | allowlist_json || exit 1
fi
```

Inspect before starting or restarting anything. Distinguish runtime reachability, Gradle readiness, reference-index readiness, and persisted graph coverage. If readiness cannot select one managed backend, report its sanitized evidence and skip only the backend-dependent status and path steps. Never echo the captured JSON. Use `allowlist_json` in the same local shell invocation before producing user-visible output; if `jq` or safe filtering is unavailable, do not run the diagnostic.

## Inspect configuration and paths locally

```shell
CONFIG_JSON="$("$KASTCTL" --output json config list --workspace-root "$PWD" 2>/dev/null)" || :
test -n "$CONFIG_JSON" || exit 1
printf '%s\n' "$CONFIG_JSON" | allowlist_json || exit 1
if [ -n "$BACKEND" ]; then
  case "$BACKEND" in
    idea) PATHS_JSON="$("$KASTCTL" --output json developer inspect paths \
      --workspace-root "$PWD" --idea 2>/dev/null)" ;;
    headless) PATHS_JSON="$("$KASTCTL" --output json developer inspect paths \
      --workspace-root "$PWD" 2>/dev/null)" ;;
  esac
fi
```

Use the first result to identify effective values and mutable fields. When present, use the second only to locate the active backend's Kast logs. Never echo either variable. Report only closed status names, booleans, allowlisted counts and durations, and the exact configuration keys named in this skill.

There is no typed log-reader command. Run searches only over the relevant time window, capture stdout into a variable, and discard stderr with `2>/dev/null`. Never echo the capture or report log-derived strings, including error codes; reduce it locally to counts and timings. Never return raw log lines, paths, project names, symbols, endpoints, tokens, process descriptors, sockets, or PIDs. Do not enable `KAST_IDEA_TRACE`; it records extensive host and workspace metadata.

## Change one supported knob at a time

Configuration and runtime mutations require explicit user authorization and an initially selected `$BACKEND`. For diagnose, explain, or inspect requests, stop after recommending the single evidence-backed key.

For an authorized one-key experiment:

1. Select exactly one key from the `config_keys` allowlist that `config list` reports as mutable. Re-run `config list` immediately before mutation. Require one successful response at the same schema version, one matching mutable field, a scalar effective value matching its declared `valueType`, and a boolean `workspaceOverride`. Keep the value and flag only in memory.
2. Register cleanup for normal exit, command failure, and HUP/INT/TERM before `config set`. Cleanup must restore with `config set` when `workspaceOverride` was true, otherwise `config unset`, and then run `developer runtime restart --backend "$BACKEND" --accept-indexing`.
3. Set the temporary value, restart the same `$BACKEND`, and reproduce once.
4. After cleanup, independently capture `config list` and `status --backend "$BACKEND"`. Require the original effective value, `valueType`, and `workspaceOverride`, plus a reachable, healthy, active runtime for that backend in `INDEXING` or `READY` at the same schema version.
5. Treat any cleanup command or verification failure as `RESTORE_FAILED`, overriding the reproduction result. Report `RESTORED` separately only after both checks pass. Never suppress cleanup status or print the captured payloads.

Route symptoms to these current mutable keys:

- request saturation: `server.requestTimeoutMillis`, `server.maxConcurrentRequests`, `server.maxResults`
- Gradle or identifier waits: `gradle.toolingApiTimeoutMillis`, `indexing.identifierIndexWaitMillis`
- relationship indexing: `indexing.relationships.enabled`, `.batchSize`, `.parallelism`, `.modulePriorityDepth`
- project lifecycle: `projectOpen.gradleLoadEnabled`, `backends.idea.enabled`, `backends.headless.enabled`, `watcher.debounceMillis`
- telemetry: `telemetry.enabled`, `.scopes`, `.detail`
- profiling: `profiling.enabled`, `.modes`, `.durationSeconds`, `.emitManifest`

Prefer one narrow telemetry scope such as `references`, `type-hierarchy`, `workspace-files`, `workspace-symbol-search`, `read-action`, or `refresh`; use `basic` detail before `verbose`. Profiling modes are `cpu`, `alloc`, `lock`, and `wall`. Configuration acceptance does not prove a profile was recorded: the current runtime parses profiling settings but has no reliable profile-capture consumer. Say so instead of promising an artifact.

## Read the source index through typed commands

Use the repository-mandated Rust graph command for generation-checked, SQLite read-only, query-only projections. Read its scoped help first.

```shell
AGENT_HELP="$("$KASTCTL" agent --help 2>/dev/null)"
GRAPH_HELP="$("$KASTCTL" agent graph --help 2>/dev/null)"
test -n "$AGENT_HELP" || exit 1
test -n "$GRAPH_HELP" || exit 1
MODULE_SUMMARY="$("$KASTCTL" --output json agent graph --workspace-root "$PWD" \
  --scope module --operation summary 2>/dev/null)"
PACKAGE_SUMMARY="$("$KASTCTL" --output json agent graph --workspace-root "$PWD" \
  --scope package --operation summary 2>/dev/null)"
printf '%s\n' "$MODULE_SUMMARY" "$PACKAGE_SUMMARY" | allowlist_json
```

Run the symbol summary only when symbol-wide analytics are specifically required and the module/package evidence justifies its cost.

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
