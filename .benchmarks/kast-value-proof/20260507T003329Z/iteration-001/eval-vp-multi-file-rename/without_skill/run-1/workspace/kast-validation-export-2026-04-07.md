# Kast validation event export for April 7, 2026

This file captures the full kast validation chronology from two passes against
the Konditional workspace on April 7, 2026. It is written as an LLM handoff
artifact, not as end-user product documentation. It includes the environment,
probe targets, raw repo-side ground truth, command timelines, observed outputs,
and the main discrepancies that still need explanation.

## Environment

The validation ran from the same workspace for both passes. The first pass
happened before a local kast reinstall. The second pass happened after the user
reinstalled kast and asked for a full rerun.

| Field | Value |
| --- | --- |
| Date | April 7, 2026 |
| Time zone | America/New_York |
| Shell | `zsh` |
| Workspace root | `/Users/amichne/code/konditional` |
| Skill consulted | `/Users/amichne/code/apollo/skills/kast/SKILL.md` |
| Skill helper script | `/Users/amichne/code/apollo/skills/kast/scripts/find-symbol-offset.py` |
| kast resolver | `/Users/amichne/code/apollo/skills/kast/scripts/resolve-kast.sh` |
| Resolved kast path | `/Users/amichne/.local/bin/kast` |
| CLI version after reinstall | `Kast CLI 0.1.1-SNAPSHOT` |
| Backend version reported by kast | `0.1.0` |
| Workspace descriptor path | `/Users/amichne/code/konditional/.kast/instances/cbd554d371f69328fa279dca5831033d22c60702914de14b23fc5f55c2247b2f.json` |
| Workspace socket path | `/Users/amichne/code/konditional/.kast/s` |

## Probe targets

The validation intentionally reused the same two symbols across both passes.
This makes the before-and-after comparison easier.

| Symbol | File | Declaration line | UTF-16 offset |
| --- | --- | --- | --- |
| `FeatureId.Companion.create` | `/Users/amichne/code/konditional/konditional-types/src/main/kotlin/io/amichne/konditional/values/FeatureId.kt` | 19 | 536 |
| `FeatureId.Companion.parse` | `/Users/amichne/code/konditional/konditional-types/src/main/kotlin/io/amichne/konditional/values/FeatureId.kt` | 30 | 966 |

The offsets came from these helper-script results:

```text
python3 /Users/amichne/code/apollo/skills/kast/scripts/find-symbol-offset.py \
  /Users/amichne/code/konditional/konditional-types/src/main/kotlin/io/amichne/konditional/values/FeatureId.kt \
  --symbol create

536  19  12          fun create(
1677 44  19              return create(namespaceSeed = NamespaceId(namespaceSeed), key = key)

python3 /Users/amichne/code/apollo/skills/kast/scripts/find-symbol-offset.py \
  /Users/amichne/code/konditional/konditional-types/src/main/kotlin/io/amichne/konditional/values/FeatureId.kt \
  --symbol parse

966  30  12          fun parse(plainId: String): FeatureId {
```

## Repo-side ground truth

Raw text search established the baseline expectation before interpreting kast
results. Another model should treat this baseline as the minimum set of known
call sites for the two probe symbols.

```text
rg -n "FeatureId\.create\(|FeatureId\.parse\(" /Users/amichne/code/konditional -g '*.kt'

/Users/amichne/code/konditional/konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/IdentifierJsonAdapter.kt:45:                FeatureId.parse(plainId)
/Users/amichne/code/konditional/konditional-types/src/main/kotlin/io/amichne/konditional/core/features/Identifiable.kt:80:                    override val id: FeatureId = FeatureId.create(namespaceId, key)
/Users/amichne/code/konditional/konditional-json/src/test/kotlin/io/amichne/konditional/json/NamespaceJsonTest.kt:66:        val unknownKey = FeatureId.create(namespace.id, "missing-flag")
/Users/amichne/code/konditional/konditional-json/src/test/kotlin/io/amichne/konditional/serialization/ConfigurationCodecTest.kt:41:        val unknownFeatureId = FeatureId.create(NamespaceId("json-main"), "unknown")
```

For `FeatureId.create`, the expected caller set includes three files outside
`FeatureId.kt`. For `FeatureId.parse`, the expected caller set includes at
least `IdentifierJsonAdapter.kt`.

## Skill and CLI mismatch

The kast skill file and the live CLI disagreed on call hierarchy support. This
may matter if another model is trying to separate tool regressions from stale
local skill instructions.

| Source | Observation |
| --- | --- |
| `/Users/amichne/code/apollo/skills/kast/SKILL.md` | States `CALL_HIERARCHY` is a known gap and says not to use `callHierarchy`. |
| `/Users/amichne/code/apollo/skills/kast/references/command-reference.md` | Mentions `CALL_HIERARCHY` as a capability enum but does not document the command body. |
| `kast --help` and `kast help call hierarchy` | Advertise `kast call hierarchy` with `incoming` and `outgoing` directions. |

The relevant CLI help text during both passes was:

```text
Analysis
  capabilities              Print the advertised capabilities for the workspace daemon.
  symbol resolve            Resolve the symbol at a file position.
  references                Find references for the symbol at a file position.
  call hierarchy            Expand a bounded call hierarchy for the symbol at a file position.
```

## Phase 1 timeline: before reinstall

This phase started from the original local install. It included some initial
parallel commands while following the skill, then a strict recovery path after
the daemon failed to start inside the sandbox.

| Event | Command | Result | Notes |
| --- | --- | --- | --- |
| P1-01 | `pwd` | Success | Confirmed workspace root was `/Users/amichne/code/konditional`. |
| P1-02 | `ls -la /Users/amichne/code/apollo/skills/kast` and `ls -la .../scripts` | Success | Confirmed `SKILL.md`, `resolve-kast.sh`, and `find-symbol-offset.py` exist. |
| P1-03 | `KAST=$(/Users/amichne/code/apollo/skills/kast/scripts/resolve-kast.sh)` | Success | Resolved kast path to `/Users/amichne/.local/bin/kast`. |
| P1-04 | `kast workspace ensure --workspace-root=/Users/amichne/code/konditional` in sandbox | Failure | Returned `DAEMON_START_FAILED`. |
| P1-05 | `kast capabilities --workspace-root=/Users/amichne/code/konditional` in sandbox | Failure | Returned `DAEMON_START_FAILED`. |
| P1-06 | `sed -n '1,220p' .kast/logs/standalone-daemon.log` | Success | Log showed a local socket bind failure inside the sandbox. |
| P1-07 | `kast workspace ensure --workspace-root=/Users/amichne/code/konditional` outside sandbox | Success | Daemon reached `READY`; early successful PID was `30314`. |
| P1-08 | `rg` against skill docs and command-reference | Success | Found that the skill says call hierarchy is not implemented, while command-reference exposes the capability name. |
| P1-09 | `kast --help` | Success | Live CLI advertised `call hierarchy`. |
| P1-10 | `kast capabilities --workspace-root=/Users/amichne/code/konditional` | Success | Reported `RESOLVE_SYMBOL`, `FIND_REFERENCES`, and `CALL_HIERARCHY`. |
| P1-11 | `kast help call hierarchy` | Success | Documented the live CLI surface for incoming and outgoing traversal. |
| P1-12 | `sed -n '1,140p' IdentifierEncoding.kt`, `sed -n '1,120p' FeatureId.kt`, and `rg` for `FeatureId.create` and `FeatureId.parse` | Success | Established the target symbols and their likely cross-file call sites. |
| P1-13 | `find-symbol-offset.py ... --symbol create` | Success | Returned declaration offset `536`. |
| P1-14 | `kast symbol resolve ... --offset=536` | Success | Resolved to `io.amichne.konditional.values.FeatureId.Companion.create`. stderr reported `INDEXING, enrichment in progress`. |
| P1-15 | `kast workspace status` and `kast workspace ensure` | Success | Re-established a clean `READY` state. A later stable PID was `30379`. |
| P1-16 | `kast references ... --offset=536 --include-declaration=true` | Partial success | Returned only the in-file call at `FeatureId.kt:44`. `searchScope` said `scope: DEPENDENT_MODULES`, `candidateFileCount: 1`, and `searchedFileCount: 1`. |
| P1-17 | `kast call hierarchy ... --offset=536 --direction=incoming --depth=3` | Partial success | Returned only `FeatureId.parse -> FeatureId.create` inside `FeatureId.kt`. `filesVisited: 1`. |
| P1-18 | `kast call hierarchy ... --offset=536 --direction=outgoing --depth=3` | Failure | Returned `INTERNAL_ERROR`: `Cannot invoke "com.intellij.psi.PsiFile.getVirtualFile()" because the return value of "com.intellij.psi.PsiElement.getContainingFile()" is null`. |
| P1-19 | `find-symbol-offset.py ... --symbol parse` | Success | Returned declaration offset `966`. |
| P1-20 | `kast symbol resolve ... --offset=966` | Success | Resolved to `io.amichne.konditional.values.FeatureId.Companion.parse`. |
| P1-21 | `kast references ... --offset=966 --include-declaration=true` | Partial success | Returned zero references. `candidateFileCount` and `searchedFileCount` were both `1`. |
| P1-22 | `kast call hierarchy ... --offset=966 --direction=incoming --depth=3` | Partial success | Returned an empty incoming tree. |
| P1-23 | `kast call hierarchy ... --offset=966 --direction=outgoing --depth=3` | Failure | Hit the same `INTERNAL_ERROR` as `create` outgoing. |
| P1-24 | `rg` and `sed` on `references/troubleshooting.md` | Success | No documented recovery path explained the missing cross-module results. |
| P1-25 | `kast help workspace refresh` | Success | Confirmed full refresh syntax. |
| P1-26 | `kast workspace refresh --workspace-root=/Users/amichne/code/konditional` | Success | Refreshed many Kotlin files across modules. |
| P1-27 | `kast workspace status` after refresh | Failure state | Selected PID `30444` was stale immediately after refresh. |
| P1-28 | `kast references ... --offset=966 --include-declaration=true` after refresh | Partial success | Still returned zero references. stderr said `INDEXING, enrichment in progress`. |
| P1-29 | `kast call hierarchy ... --offset=966 --direction=incoming --depth=3` after refresh | Partial success | Still returned an empty incoming tree. stderr said `INDEXING, enrichment in progress`. |
| P1-30 | `kast workspace ensure --workspace-root=/Users/amichne/code/konditional` | Success | Reached `READY` again. A later stable PID was `30469`. |
| P1-31 | Final rerun of `references` and incoming hierarchy for `parse` | Partial success | Results did not change after refresh and re-ensure. |
| P1-32 | Final rerun of outgoing hierarchy for `parse` | Failure | Still produced the same PSI `INTERNAL_ERROR`. |
| P1-33 | `kast daemon stop --workspace-root=/Users/amichne/code/konditional` | Success | Stopped the daemon cleanly. One recorded stop used PID `30482`. |

The initial daemon log excerpt from the sandbox failure was:

```text
Exception in thread "main" java.net.SocketException: Operation not permitted
    at java.base/sun.nio.ch.UnixDomainSockets.bind0(Native Method)
    at java.base/sun.nio.ch.UnixDomainSockets.bind(UnixDomainSockets.java:115)
    at java.base/sun.nio.ch.ServerSocketChannelImpl.unixBind(ServerSocketChannelImpl.java:326)
    at java.base/sun.nio.ch.ServerSocketChannelImpl.bind(ServerSocketChannelImpl.java:299)
    at java.base/java.nio.channels.ServerSocketChannel.bind(ServerSocketChannel.java:224)
    at io.github.amichne.kast.server.UnixDomainSocketRpcServer.start(LocalRpcServer.kt:48)
```

## Phase 1 findings

Before reinstall, the main issues were clear and repeatable.

1. The daemon could not start inside the sandbox because it failed to bind its
   Unix domain socket.
2. `symbol resolve` worked once the daemon was running outside the sandbox.
3. `references` missed known cross-module callers for both probe symbols.
4. Incoming call hierarchy only found same-file data and missed known external
   callers.
5. Outgoing call hierarchy consistently crashed with a PSI
   `getContainingFile() == null` error.
6. `workspace refresh` did not fix the missing references and left the daemon
   descriptor stale once.

## Phase 2 timeline: after reinstall

This phase started after the user reinstalled kast and asked for a full rerun.
I first repeated the earlier commands, then reran the probe suite serially
because the skill says commands should be run sequentially. One turn was
interrupted partway through and then resumed from a fresh `workspace ensure`.

| Event | Command | Result | Notes |
| --- | --- | --- | --- |
| P2-01 | `pwd`, `bash /Users/amichne/code/apollo/skills/kast/scripts/resolve-kast.sh`, and both `find-symbol-offset.py` invocations | Success | Resolved the same binary path and the same offsets: `536` and `966`. |
| P2-02 | `kast --version` | Success | Reported `Kast CLI 0.1.1-SNAPSHOT`. |
| P2-03 | `kast workspace ensure --workspace-root=/Users/amichne/code/konditional` in sandbox | Success | This is the first clear improvement. The daemon reached `READY` inside the sandbox with PID `33673`. |
| P2-04 | `kast capabilities`, `kast help call hierarchy`, and both `symbol resolve` probes | Success | Capabilities and symbol resolution still worked. stderr still sometimes said `INDEXING, enrichment in progress`. |
| P2-05 | `kast workspace status` | Failure state | Descriptor PID `33693` was already stale before the next matrix run. |
| P2-06 | `kast workspace ensure --workspace-root=/Users/amichne/code/konditional` | Success | Reached `READY` again with PID `33720`. |
| P2-07 | `kast references ... --offset=536 --include-declaration=true` | Partial success | Same result as before reinstall: only the in-file call at `FeatureId.kt:44`; `candidateFileCount: 1`. |
| P2-08 | `kast call hierarchy ... --offset=536 --direction=incoming --depth=3` | Partial success | Same result as before reinstall: only `FeatureId.parse -> FeatureId.create` inside `FeatureId.kt`. |
| P2-09 | `kast call hierarchy ... --offset=536 --direction=outgoing --depth=3` | Success with caveats | No crash. Returned a very large graph, about 6,588 lines of output, with `totalNodes: 257`, `totalEdges: 256`, `truncatedNodes: 55`, `filesVisited: 4`, and `MAX_TOTAL_CALLS`. |
| P2-10 | `kast references ... --offset=966 --include-declaration=true` | Partial success | Still returned zero references and `candidateFileCount: 1`. |
| P2-11 | `kast call hierarchy ... --offset=966 --direction=incoming --depth=3` | Partial success | Still returned an empty incoming tree. |
| P2-12 | `kast call hierarchy ... --offset=966 --direction=outgoing --depth=3` | Success with caveats | No crash. Returned about 6,506 lines of output, with `totalNodes: 257`, `totalEdges: 256`, `truncatedNodes: 35`, `filesVisited: 3`, and `MAX_TOTAL_CALLS`. |
| P2-13 | `kast workspace status` | Failure state | Descriptor PID `33737` was stale after the matrix run. |
| P2-14 | `rg -n "FeatureId\.create\(|FeatureId\.parse\(" ...` | Success | Reconfirmed that repo-side ground truth still contains external callers kast did not return. |
| P2-15 | `kast daemon stop --workspace-root=/Users/amichne/code/konditional` | Success | Stopped PID `33737` explicitly before the strict serial rerun. |
| P2-16 | `kast workspace ensure --workspace-root=/Users/amichne/code/konditional` | Success | Reached `READY` with PID `34056`. |
| P2-17 | `kast capabilities` | Success | Same capability set. stderr said `state: READY`. |
| P2-18 | `kast symbol resolve ... --offset=536` | Success | Same `FeatureId.Companion.create` result. |
| P2-19 | `kast references ... --offset=536 --include-declaration=true` | Partial success | Same single in-file reference result. stderr again mentioned `INDEXING, enrichment in progress`. |
| P2-20 | `kast call hierarchy ... --offset=536 --direction=incoming --depth=3` | Partial success | Same single in-file caller result. |
| P2-21 | `kast call hierarchy ... --offset=536 --direction=outgoing --depth=3` | Success with caveats | Same large bounded graph as P2-09. |
| P2-22 | `kast symbol resolve ... --offset=966` | Success | Same `FeatureId.Companion.parse` result. The user interrupted the turn after this command. |
| P2-23 | `kast workspace status` after interruption | Failure state | Descriptor PID `34094` was stale when the run resumed. |
| P2-24 | `kast workspace ensure --workspace-root=/Users/amichne/code/konditional` | Success | Reached `READY` again with PID `34115`. |
| P2-25 | `kast references ... --offset=966 --include-declaration=true` | Partial success | Still returned zero references with `candidateFileCount: 1`. |
| P2-26 | `kast call hierarchy ... --offset=966 --direction=incoming --depth=3` | Partial success | Still returned an empty incoming tree. stderr again mentioned `INDEXING, enrichment in progress`. |
| P2-27 | `kast call hierarchy ... --offset=966 --direction=outgoing --depth=3` | Success with caveats | Same large bounded graph as P2-12. |
| P2-28 | `kast daemon stop --workspace-root=/Users/amichne/code/konditional` | Success | Stopped PID `34138` as cleanup. |

## Phase 2 findings

The reinstall changed some important behaviors, but not all of them.

1. The sandbox startup problem appears fixed. `workspace ensure` now succeeds
   without escalation.
2. Outgoing call hierarchy no longer throws the PSI null-file exception.
3. `references` is still incomplete for known external callers.
4. Incoming call hierarchy is still incomplete for known external callers.
5. Daemon lifecycle stability is still poor. The descriptor repeatedly becomes
   stale between commands, even in the serial rerun.
6. stderr still often reports `INDEXING, enrichment in progress` from commands
   that follow a prior `workspace ensure` that already reported `READY`.

## Representative outputs that still look suspicious

The outgoing hierarchy now works, but its results look semantically noisy. This
may be correct by design, or it may indicate that the traversal includes more
than actual call edges.

For `FeatureId.Companion.create`, the outgoing hierarchy includes all of the
following near the top of the graph:

- `NamespaceId` from the parameter type in the function signature
- `IdentifierEncoding.SEPARATOR`
- `Identifiable.Named.Composable`
- local parameters such as `prefix`, `components`, and `index`
- doc-comment references such as `"[SEPARATOR]"` previews

For `FeatureId.Companion.parse`, the outgoing hierarchy includes all of the
following near the top of the graph:

- the class `FeatureId`
- the local property `parts`
- the object `IdentifierEncoding`
- `IdentifierEncoding.split`
- `NamespaceId`
- multiple cycles on the class `FeatureId`

These outputs may indicate that outgoing hierarchy is traversing symbol mentions
from signatures, doc comments, local variables, and other semantic edges beyond
direct calls.

## Stable discrepancies between kast and repo ground truth

These discrepancies persisted after reinstall and after a strict serial rerun.

| Probe | Repo-side expectation | kast result |
| --- | --- | --- |
| `FeatureId.create` references | `Identifiable.kt:80`, `NamespaceJsonTest.kt:66`, `ConfigurationCodecTest.kt:41`, and the local recursive call at `FeatureId.kt:44` | Only the local recursive call at `FeatureId.kt:44` |
| `FeatureId.create` incoming hierarchy | At least the same callers listed above | Only `FeatureId.parse -> FeatureId.create` in `FeatureId.kt` |
| `FeatureId.parse` references | `IdentifierJsonAdapter.kt:45` | No references |
| `FeatureId.parse` incoming hierarchy | At least `IdentifierJsonAdapter.kt:45` | Empty incoming tree |

## Candidate root causes for another model to investigate

These are hypotheses, not conclusions. They are included because they are
directly suggested by the observed event stream.

1. Workspace indexing may not be traversing dependent modules correctly for
   `FIND_REFERENCES` and incoming call hierarchy, even though the CLI reports
   `scope: DEPENDENT_MODULES`.
2. The daemon may be recycling unexpectedly between commands, which could reset
   index state and explain the repeated stale descriptors and repeated
   `INDEXING` messages after `READY`.
3. Outgoing hierarchy may now be using a broader semantic graph than strict
   call edges, which would explain the inclusion of parameter types, local
   variables, documentation previews, and cycles.
4. The backend version remaining at `0.1.0` while the CLI is
   `0.1.1-SNAPSHOT` might indicate a mismatch between the launcher and the
   packaged backend bits.
5. `workspace refresh` may not preserve daemon liveness cleanly, because it
   previously left the selected descriptor stale immediately afterward.

## Minimal command set to reproduce

If another model wants the shortest path to the current issues, this set is
enough to reproduce the important post-reinstall behavior.

```text
cd /Users/amichne/code/konditional

bash /Users/amichne/code/apollo/skills/kast/scripts/resolve-kast.sh

"/Users/amichne/.local/bin/kast" workspace ensure --workspace-root=/Users/amichne/code/konditional

"/Users/amichne/.local/bin/kast" capabilities --workspace-root=/Users/amichne/code/konditional

"/Users/amichne/.local/bin/kast" symbol resolve \
  --workspace-root=/Users/amichne/code/konditional \
  --file-path=/Users/amichne/code/konditional/konditional-types/src/main/kotlin/io/amichne/konditional/values/FeatureId.kt \
  --offset=536

"/Users/amichne/.local/bin/kast" references \
  --workspace-root=/Users/amichne/code/konditional \
  --file-path=/Users/amichne/code/konditional/konditional-types/src/main/kotlin/io/amichne/konditional/values/FeatureId.kt \
  --offset=536 \
  --include-declaration=true

"/Users/amichne/.local/bin/kast" call hierarchy \
  --workspace-root=/Users/amichne/code/konditional \
  --file-path=/Users/amichne/code/konditional/konditional-types/src/main/kotlin/io/amichne/konditional/values/FeatureId.kt \
  --offset=536 \
  --direction=incoming \
  --depth=3

"/Users/amichne/.local/bin/kast" call hierarchy \
  --workspace-root=/Users/amichne/code/konditional \
  --file-path=/Users/amichne/code/konditional/konditional-types/src/main/kotlin/io/amichne/konditional/values/FeatureId.kt \
  --offset=536 \
  --direction=outgoing \
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

"/Users/amichne/.local/bin/kast" call hierarchy \
  --workspace-root=/Users/amichne/code/konditional \
  --file-path=/Users/amichne/code/konditional/konditional-types/src/main/kotlin/io/amichne/konditional/values/FeatureId.kt \
  --offset=966 \
  --direction=outgoing \
  --depth=3

rg -n "FeatureId\.create\(|FeatureId\.parse\(" /Users/amichne/code/konditional -g '*.kt'
```

## Closing state

The final cleanup step for the resumed serial rerun was:

```text
"/Users/amichne/.local/bin/kast" daemon stop --workspace-root=/Users/amichne/code/konditional

{
  "workspaceRoot": "/Users/amichne/code/konditional",
  "stopped": true,
  "pid": 34138,
  "forced": false,
  "schemaVersion": 2
}
```

At the end of the export, no code files had been modified by the validation
workflow itself.
