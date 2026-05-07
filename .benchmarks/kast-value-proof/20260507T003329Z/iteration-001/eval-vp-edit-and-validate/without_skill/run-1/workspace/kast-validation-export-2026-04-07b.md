# Kast validation event export for April 7, 2026 (third session)

This file captures the full kast validation chronology from two passes against
the Konditional workspace on April 7, 2026, continuing from
`kast-validation-export-2026-04-07.md` and the second-session export. It is
written as an LLM handoff artifact, not as end-user product documentation. It
includes the environment, probe targets, raw repo-side ground truth, command
timelines, observed outputs, OTEL telemetry observations, and the updated
set of discrepancies and hypotheses.

The user initiated this session by asking for a rerun of the original evaluation
against the same probe targets, specifying that the expected CLI version was
`f57591d81d86ace19ecbb323bea90e416512d96b`. Both prior-artifact wipe and the
new `kast smoke` workflow were included in this session.

## Environment

Prior kast artifacts were wiped before Phase 1 started, giving a cold-index
baseline. Phase 2 exercised the new `kast smoke` command against all discovered
source sets. The smoke workflow stopped the daemon as its final step, so a fresh
`workspace ensure` was issued to restore a known clean state at the end.

| Field | Value |
| --- | --- |
| Date | April 7, 2026 |
| Time zone | America/New_York |
| Shell | `zsh` |
| Workspace root | `/Users/amichne/code/konditional` |
| Skill consulted | `/Users/amichne/code/konditional/.agents/skills/kast/SKILL.md` |
| Skill version | `0.1.1-SNAPSHOT` (from `.kast-version`) |
| kast resolver | `/Users/amichne/code/konditional/.agents/skills/kast/scripts/resolve-kast.sh` |
| Resolved kast path | `/Users/amichne/.local/bin/kast` |
| CLI version | `f57591d81d86ace19ecbb323bea90e416512d96b` |
| Backend version | `1cf3296932a1fe9fddeecb7844a87adeb71bd073+dirty` |
| Phase 1 daemon PID | `51397` |
| Phase 2 smoke daemon PID | `53040` |
| Closing daemon PID (post-smoke re-ensure) | `53707` |
| Workspace socket path | `/Users/amichne/code/konditional/.kast/s` |
| Workspace descriptor path | `/Users/amichne/code/konditional/.kast/instances/cbd554d371f69328fa279dca5831033d22c60702914de14b23fc5f55c2247b2f.json` |
| Daemon log path | `/Users/amichne/code/konditional/.kast/logs/standalone-daemon.log` |

## Probe targets

The same two symbols from both prior exports were reused to keep the
cross-session comparison unambiguous.

| Symbol | File | Declaration line | UTF-16 offset |
| --- | --- | --- | --- |
| `FeatureId.Companion.create` | `/Users/amichne/code/konditional/konditional-types/src/main/kotlin/io/amichne/konditional/values/FeatureId.kt` | 19 | 536 |
| `FeatureId.Companion.parse` | `/Users/amichne/code/konditional/konditional-types/src/main/kotlin/io/amichne/konditional/values/FeatureId.kt` | 30 | 966 |

Offsets were reconfirmed with an inline `python3 -c` invocation. The declaration
file is unchanged from prior sessions.

## Repo-side ground truth

The same `rg` query confirms the caller baseline is unchanged.

```text
rg -n "FeatureId\.(create|parse)\(" /Users/amichne/code/konditional -g '*.kt'

/Users/amichne/code/konditional/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/IdentifierJsonAdapter.kt:45:                FeatureId.parse(plainId)
/Users/amichne/code/konditional/konditional-types/src/main/kotlin/io/amichne/konditional/core/features/Identifiable.kt:80:                    override val id: FeatureId = FeatureId.create(namespaceId, key)
/Users/amichne/code/konditional/konditional-json/src/test/kotlin/io/amichne/konditional/json/NamespaceJsonTest.kt:66:        val unknownKey = FeatureId.create(namespace.id, "missing-flag")
/Users/amichne/code/konditional/konditional-json/src/test/kotlin/io/amichne/konditional/serialization/ConfigurationCodecTest.kt:41:        val unknownFeatureId = FeatureId.create(NamespaceId("json-main"), "unknown")
```

For `FeatureId.create`, the expected caller set includes three files outside
`FeatureId.kt`. For `FeatureId.parse`, the expected caller set includes at
least `IdentifierJsonAdapter.kt`.

## Infrastructure changes relative to prior session

Several changes appeared with the new CLI build.

| Field | Prior session | This session |
| --- | --- | --- |
| CLI version | `0.1.1-SNAPSHOT` | `f57591d81d86ace19ecbb323bea90e416512d96b` |
| Backend version | `0.1.0` | `1cf3296932a1fe9fddeecb7844a87adeb71bd073+dirty` |
| Artifact root | `.gradle/kast/` | `.kast/` (reverted to original location) |
| `smoke` command | Not present | Present |
| `runtimeStatus.sourceModuleNames` | Not present | Present (8 modules) |
| `runtimeStatus.dependentModuleNamesBySourceModuleName` | Not present | Present (full graph) |
| SLF4J warnings in daemon log | Present | Absent |
| Skill version file | `0.1.1-SNAPSHOT` | `0.1.1-SNAPSHOT` (unchanged) |

### Module dependency graph now visible

`workspace ensure` now returns `sourceModuleNames` and
`dependentModuleNamesBySourceModuleName` inside `runtimeStatus`. The full
workspace ensure response for this session is reproduced below.

The dependent module map for `:konditional-types[main]` — the module that
contains both probe symbols — lists eight dependents:

```text
":konditional-types[main]": [
    ":konditional-engine[main]",
    ":konditional-engine[testFixtures]",
    ":konditional-engine[test]",
    ":konditional-json[main]",
    ":konditional-json[test]",
    ":konditional-types[main]",
    ":konditional-types[test]",
    ":smoke-test[test]"
]
```

All eight files containing known callers of `FeatureId.create` and
`FeatureId.parse` are in modules listed in this graph. Despite this, reference
searches still return `candidateFileCount: 1`. See the discrepancies section.

### New `kast smoke` command

A `kast smoke` command is now available. It runs a structured readiness check
against all discovered source sets, exercising symbol resolve, references, call
hierarchy incoming and outgoing, diagnostics, rename dry-run, and workspace
refresh for one randomly-selected symbol per source set. Results are emitted as
JSON or Markdown. The command also stops the daemon as its final step.

### kast binary used by smoke

The `kast smoke` command delegates to
`/Users/amichne/.local/share/kast/main/kast` rather than the resolved binary at
`/Users/amichne/.local/bin/kast`. Both binaries report the same CLI version
(`f57591d81d86ace19ecbb323bea90e416512d96b`).

### OTEL observations

OpenTelemetry JARs are present in the kast runtime-libs directories across
multiple named instances (`spruce-raven`, `agile-swift`, releases `v0.1.0` and
`v0.2.0`, and the current `main` install). The bundled libraries include:

```text
opentelemetry-api-1.49.0.jar
opentelemetry-context-1.49.0.jar
opentelemetry-sdk-1.49.0.jar
opentelemetry-sdk-common-1.49.0.jar
opentelemetry-sdk-logs-1.49.0.jar
opentelemetry-sdk-metrics-1.49.0.jar
opentelemetry-sdk-trace-1.49.0.jar
opentelemetry.jar
intellij.platform.diagnostic.telemetry.agent.extension.jar
```

No file-based OTEL export was observed during either phase. The daemon log
contained no OTEL trace or span output. No OTEL output files were found in
`.kast/`, `.gradle/kast/`, or `~/.local/share/kast/`. The OTEL SDK appears to
be bundled but exporting to a no-op or in-memory sink in this build.

The full daemon log for the Phase 1 daemon (PID `51397`) was:

```text
WARN: Attempt to load key 'java.highest.language.level.restartRequired' for not yet loaded registry
kast standalone listening on /Users/amichne/code/konditional/.kast/s
descriptor: ServerInstanceDescriptor(workspaceRoot=/Users/amichne/code/konditional, backendName=standalone, backendVersion=1cf3296932a1fe9fddeecb7844a87adeb71bd073+dirty, transport=uds, socketPath=/Users/amichne/code/konditional/.kast/s, pid=51397, schemaVersion=2)
```

The SLF4J multiple-provider warnings seen in both prior sessions are absent in
this build.

## Phase 1 timeline: cold-start probe run

Prior artifacts (both `.kast/` and `.gradle/kast/`) were removed before Phase 1
started. One daemon (PID `41811`) was running from the prior session; it was
stopped before artifact removal. Phase 1 ran the full manual probe suite
serially from a fresh daemon.

| Event | Command | Result | Notes |
| --- | --- | --- | --- |
| R1-01 | `kast daemon stop` and `rm -rf .kast .gradle/kast` | Success | Prior daemon (from second session) was not running (`stopped: false`). Both artifact directories confirmed absent. |
| R1-02 | `resolve-kast.sh` and `kast --version` | Success | Resolved binary to `/Users/amichne/.local/bin/kast`. Reported `f57591d81d86ace19ecbb323bea90e416512d96b`. |
| R1-03 | Offset computation via inline `python3 -c` | Success | Confirmed offsets `536` and `966` are unchanged. |
| R1-04 | `kast workspace ensure --workspace-root=/Users/amichne/code/konditional` | Success | Daemon started as PID `51397`. Artifacts written to `.kast/` (reverted from `.gradle/kast/`). State `READY`, `indexing: false`. `runtimeStatus` now includes `sourceModuleNames` and `dependentModuleNamesBySourceModuleName`. |
| R1-05 | `kast symbol resolve ... --offset=536` | Success | Resolved to `io.amichne.konditional.values.FeatureId.Companion.create`, line 19. No `INDEXING` message on stderr. |
| R1-06 | `kast references ... --offset=536 --include-declaration=true` | Partial success | Returned only the in-file recursive call at `FeatureId.kt:44`. `scope: DEPENDENT_MODULES`, `candidateFileCount: 1`, `searchedFileCount: 1`. Module graph was populated in workspace ensure but the search still visited only 1 file. |
| R1-07 | `kast call hierarchy ... --offset=536 --direction=incoming --depth=3` | Partial success | Returned only `FeatureId.parse -> FeatureId.create` in `FeatureId.kt`. `totalNodes: 2`, `totalEdges: 1`, `filesVisited: 1`. |
| R1-08 | `kast call hierarchy ... --offset=536 --direction=outgoing --depth=3` | Success with caveats | No crash. `totalNodes: 257`, `totalEdges: 256`, `truncatedNodes: 55`, `filesVisited: 4`, `MAX_TOTAL_CALLS`. Six direct children include parameter type, return type, and object reference alongside the actual `IdentifierEncoding.encode` call edge. |
| R1-09 | `kast symbol resolve ... --offset=966` | Success | Resolved to `io.amichne.konditional.values.FeatureId.Companion.parse`, line 30. |
| R1-10 | `kast references ... --offset=966 --include-declaration=true` | Partial success | Returned zero references. `candidateFileCount: 1`, `searchedFileCount: 1`. |
| R1-11 | `kast call hierarchy ... --offset=966 --direction=incoming --depth=3` | Partial success | Returned an empty incoming tree. `totalNodes: 1`, `totalEdges: 0`, `filesVisited: 1`. |
| R1-12 | `kast call hierarchy ... --offset=966 --direction=outgoing --depth=3` | Success with caveats | No crash. `totalNodes: 257`, `totalEdges: 256`, `truncatedNodes: 35`, `filesVisited: 3`, `MAX_TOTAL_CALLS`. Sixteen direct children include local property `parts`, companion constants, and multiple references to `IdentifierEncoding`. |
| R1-13 | `kast workspace status` after full probe run | Success | PID `51397` still alive, reachable, and `READY`. No stale descriptor observed. |

## Phase 1 findings

1. The CLI version is now a full git SHA (`f57591d81d86ace19ecbb323bea90e416512d96b`)
   and the backend version is also a git SHA
   (`1cf3296932a1fe9fddeecb7844a87adeb71bd073+dirty`). The prior version strings
   (`0.1.1-SNAPSHOT` and `0.1.0`) are gone.
2. Artifact location reverted to `.kast/` after being `.gradle/kast/` in the
   prior session.
3. `runtimeStatus` now includes `sourceModuleNames` (8 source sets) and the
   full `dependentModuleNamesBySourceModuleName` map. The map correctly shows
   `:konditional-types[main]` with 8 dependents, which includes all modules
   containing known callers of the two probe symbols.
4. Despite the module graph being correctly populated in the workspace status
   response, reference searches still return `candidateFileCount: 1`. The graph
   is visible but is not yet being used to expand the candidate file set for
   `FIND_REFERENCES` or incoming `CALL_HIERARCHY`.
5. Daemon lifecycle stability remains good. PID `51397` survived all probes
   with no stale descriptor events.
6. No `INDEXING` messages appeared on stderr in any probe command.
7. Outgoing call hierarchy results are bit-for-bit identical to prior sessions:
   257 nodes, 256 edges, same `truncatedNodes` counts, same direct children.

## Phase 2 timeline: kast smoke workflow

Phase 2 ran `kast smoke` against the workspace to exercise the full
readiness check suite. The smoke command was new in this build and had not been
run in prior sessions. It uses a randomly-selected symbol per discovered source
set and stops the daemon as its final step.

| Event | Command | Result | Notes |
| --- | --- | --- | --- |
| R2-01 | `kast smoke --workspace-root=/Users/amichne/code/konditional` | Failure | Exit code 1. `status: FAIL`, `checksFailed: 19`, `sourceSetsTested: 9`, `llmReady: false`. All 19 failures belong to `:detekt-rules:main`. |
| R2-02 | Smoke step 1: `kast --version` | Success | Confirmed `f57591d81d86ace19ecbb323bea90e416512d96b`. Smoke noted it was using `/Users/amichne/.local/share/kast/main/kast`. |
| R2-03 | Smoke step 2: `workspace ensure` | Success | Daemon started as PID `53040`. |
| R2-04 | Smoke step 3: `workspace status` | Success | |
| R2-05 | Smoke step 4: `capabilities` | Success | Same capability set as Phase 1. |
| R2-06 | Smoke step 5: source set discovery | Success | Discovered 9 source sets with at least one symbol each. |
| R2-07 | Smoke source set `:detekt-rules:main` — all checks | Failure | Every kast command returned `NOT_FOUND: The requested file is not part of the standalone analysis session`. The `detekt-rules` subproject is not listed in `sourceModuleNames` and is not indexed by the daemon. Secondary Python `JSONDecodeError` failures cascaded from the primary `NOT_FOUND` because the smoke script tried to parse result files that were not written when a command exits non-zero. |
| R2-08 | Smoke source sets 2–9 (all non-detekt source sets) | Success | All 21 checks per source set passed for `:konditional-engine:main`, `:konditional-engine:test`, `:konditional-engine:testFixtures`, `:konditional-json:main`, `:konditional-json:test`, `:konditional-types:main`, `:konditional-types:test`, and `:smoke-test:test`. |
| R2-09 | Smoke step 13: assemble report | Success | JSON report emitted. |
| R2-10 | Smoke step 14: `daemon stop` | Success | Daemon PID `53040` stopped cleanly. |
| R2-11 | `kast workspace ensure --workspace-root=/Users/amichne/code/konditional` | Success | Daemon restarted as PID `53707` for closing state. |

The smoke source set results were:

| Source set | Symbol tested | refs | callNodes | diags | renameEdits | Healthy |
| --- | --- | --- | --- | --- | --- | --- |
| `:detekt-rules:main` | `UnclosedCriteriaFirstRule` | — | — | — | — | No |
| `:konditional-engine:main` | `NamespaceRuleSet` | 12 | 17 | 0 | 13 | Yes |
| `:konditional-engine:test` | `rampUpBucketingIsDeterministicForStableId` | 0 | 1 | 42 | 1 | Yes |
| `:konditional-engine:testFixtures` | `RetryPolicy` | 0 | 1 | 4 | 1 | Yes |
| `:konditional-json:main` | `SchemaValueCodec` | 10 | 26 | 0 | 11 | Yes |
| `:konditional-json:test` | `strictRoundTripPreservesPrimitiveEnumAndKonstrainedValues` | 0 | 1 | 47 | 1 | Yes |
| `:konditional-types:main` | `split` | 1 | 6 | 0 | 2 | Yes |
| `:konditional-types:test` | `Config` | 0 | 1 | 56 | 1 | Yes |
| `:smoke-test:test` | `SmokeTest` | 0 | 1 | 0 | 1 | Yes |

## Phase 2 findings

1. `kast smoke` exits non-zero due exclusively to `:detekt-rules:main`. The
   `detekt-rules` subproject is not included in the daemon's indexed session.
   Its files return `NOT_FOUND` for every analysis command. The subproject is not
   listed in `sourceModuleNames` in the workspace ensure response, confirming it
   is structurally excluded from the session.
2. All 8 non-detekt source sets pass all 21 smoke checks. The analysis pipeline
   is healthy for the indexed portion of the workspace.
3. The smoke script has a secondary bug: when a kast command exits non-zero, the
   result file is empty, and subsequent Python inline checks that try to parse it
   produce `JSONDecodeError` failures that are counted as additional check
   failures. The 19 total failures are 4 direct `NOT_FOUND` returns plus 15
   cascaded Python parse errors from downstream checks in `:detekt-rules:main`.
4. The smoke workflow stops the daemon as its final step, which is not documented
   in the `kast smoke --help` output.
5. The smoke binary path (`/Users/amichne/.local/share/kast/main/kast`) differs
   from the resolver path (`/Users/amichne/.local/bin/kast`). Both report the
   same version.

## Representative outputs that still look suspicious

The outgoing call hierarchy output is unchanged in character from both prior
exports. It is now confirmed deterministic across three separate cold-index
daemon starts.

For `FeatureId.Companion.create`, the six direct root children are:

- `io.amichne.konditional.values.NamespaceId` (parameter type, `FeatureId.kt:20`)
- `io.amichne.konditional.values.FeatureId` (return type, `FeatureId.kt:22`)
- `FeatureId` (unqualified alias, `FeatureId.kt:23`)
- `io.amichne.konditional.values.IdentifierEncoding` (object reference, `FeatureId.kt:23`)
- `io.amichne.konditional.values.IdentifierEncoding.encode` (call edge, `FeatureId.kt:23`)
- `prefix` (local variable, `FeatureId.kt:23`)

For `FeatureId.Companion.parse`, the 16 direct root children include the class
`FeatureId`, local property `parts`, `IdentifierEncoding`, `IdentifierEncoding.split`,
`NamespaceId`, companion constants `EXPECTED_PARTS` and `PREFIX`,
`IdentifierEncoding.SEPARATOR` (appears twice), and local parameter `plainId`.

## Stable discrepancies between kast and repo ground truth

These discrepancies have been present across every session and persist on a cold
index with a correctly populated module dependency graph.

| Probe | Repo-side expectation | kast result |
| --- | --- | --- |
| `FeatureId.create` references | `Identifiable.kt:80`, `NamespaceJsonTest.kt:66`, `ConfigurationCodecTest.kt:41`, and `FeatureId.kt:44` | Only `FeatureId.kt:44` |
| `FeatureId.create` incoming hierarchy | At least the same callers listed above | Only `FeatureId.parse -> FeatureId.create` in `FeatureId.kt` |
| `FeatureId.parse` references | `IdentifierJsonAdapter.kt:45` | No references |
| `FeatureId.parse` incoming hierarchy | At least `IdentifierJsonAdapter.kt:45` | Empty incoming tree |

## Candidate root causes for another model to investigate

The prior hypotheses about daemon recycling and stale enrichment are closed.
The remaining and updated hypotheses are below.

1. The module graph is built and surfaced in `runtimeStatus` but is not
   consumed by `FIND_REFERENCES` and incoming `CALL_HIERARCHY` at query time.
   The `dependentModuleNamesBySourceModuleName` for `:konditional-types[main]`
   correctly lists 8 dependent modules, all of which contain known callers. But
   `candidateFileCount: 1` means the reference search only considers one file.
   The graph population and the candidate-file expansion are two separate code
   paths, and only the former has been updated. Another model should look at how
   `candidateFileCount` is computed in the `FIND_REFERENCES` implementation and
   whether it reads from the module graph or from a separate index.
2. The `backendVersion` carries `+dirty` in this build
   (`1cf3296932a1fe9fddeecb7844a87adeb71bd073+dirty`). If local uncommitted
   changes exist in the backend source tree, the fix for candidate-file expansion
   may be in-progress but not yet committed. The `+dirty` suffix is a signal
   worth checking against the backend source before closing this hypothesis.
3. `:detekt-rules` is excluded from the indexed session. It does not appear in
   `sourceModuleNames`. This may be intentional (detekt is a build tool
   dependency, not a first-party analysis target) or it may indicate that the
   session's Gradle build model ingestion does not walk all subprojects. Either
   way it is the sole cause of all 19 smoke failures.
4. The smoke script's empty-file parse errors are a secondary bug independent of
   the backend. When a kast command exits non-zero the script should skip
   downstream JSON-parse checks rather than attempting them and counting each
   as an independent failure.
5. Outgoing call hierarchy continues to traverse edges beyond strict call edges.
   This is confirmed deterministic and identical across three cold-index sessions.
   Whether it is intentional design or unintended scope widening remains unclear.

## Minimal command set to reproduce

The key observable remains `candidateFileCount: 1` in the `searchScope` block.
Now that the module graph is visible in `workspace ensure` output, a confirming
run should check both that `dependentModuleNamesBySourceModuleName` lists the
expected eight dependents for `:konditional-types[main]` and that
`candidateFileCount` is still `1` in the subsequent references call.

```text
cd /Users/amichne/code/konditional

# wipe prior artifacts for a clean-start signal
"/Users/amichne/.local/bin/kast" daemon stop \
  --workspace-root=/Users/amichne/code/konditional
rm -rf .kast .gradle/kast

bash /Users/amichne/code/konditional/.agents/skills/kast/scripts/resolve-kast.sh

"/Users/amichne/.local/bin/kast" workspace ensure \
  --workspace-root=/Users/amichne/code/konditional
# Inspect runtimeStatus.dependentModuleNamesBySourceModuleName[":konditional-types[main]"]
# Should list 8 dependents including :konditional-json[main] and its test

"/Users/amichne/.local/bin/kast" references \
  --workspace-root=/Users/amichne/code/konditional \
  --file-path=/Users/amichne/code/konditional/konditional-types/src/main/kotlin/io/amichne/konditional/values/FeatureId.kt \
  --offset=536 \
  --include-declaration=true
# Watch searchScope.candidateFileCount — still 1 despite 8 dependents in the graph

"/Users/amichne/.local/bin/kast" call hierarchy \
  --workspace-root=/Users/amichne/code/konditional \
  --file-path=/Users/amichne/code/konditional/konditional-types/src/main/kotlin/io/amichne/konditional/values/FeatureId.kt \
  --offset=536 \
  --direction=incoming \
  --depth=3

"/Users/amichne/.local/bin/kast" references \
  --workspace-root=/Users/amichne/code/konditional \
  --file-path=/Users/amichne/code/konditional/konditional-types/src/main/kotlin/io/amichne/konditional/values/FeatureId.kt \
  --offset=966 \
  --include-declaration=true

"/Users/amichne/.local/bin/kast" call hierarchy \
  --workspace-root=/Users/amichne/code/konditional \
  --file-path=/Users/amichne/code/konditional/konditional-types/src/main/kotlin/io/amichne/konditional/values/FeatureId.kt \
  --offset=966 \
  --direction=incoming \
  --depth=3

rg -n "FeatureId\.(create|parse)\(" /Users/amichne/code/konditional -g '*.kt'

# Optionally run the smoke suite
"/Users/amichne/.local/bin/kast" smoke \
  --workspace-root=/Users/amichne/code/konditional
# Expected: FAIL with 19 checks failed, all from :detekt-rules:main
```

## Closing state

The smoke workflow stopped the daemon as its final step. A fresh `workspace ensure`
was run after Phase 2 to restore a known clean state.

```text
"/Users/amichne/.local/bin/kast" workspace ensure \
  --workspace-root=/Users/amichne/code/konditional

{
  "workspaceRoot": "/Users/amichne/code/konditional",
  "started": true,
  "pid": 53707,
  "pidAlive": true,
  "reachable": true,
  "ready": true,
  "state": "READY",
  "backendVersion": "1cf3296932a1fe9fddeecb7844a87adeb71bd073+dirty"
}
```

At the end of the export, no code files in the workspace had been modified by
either validation pass.
