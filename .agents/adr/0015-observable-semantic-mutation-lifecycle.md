# ADR 0015: Synchronous semantic mutation execution

Status: Accepted

Date: 2026-07-13

This ADR supersedes ADR 0006 and ADR 0009 only for execution of public typed
semantic mutations. Their plan-first command boundary, hidden raw transport,
and remaining product rules stay authoritative.

## Decision

Every public `kast agent` semantic mutation that crosses the `--apply` gate
waits for one terminal `KastMutationExecutionResult`. Submission requires a
caller-chosen idempotency key. The backend owns execution after admission, so
disconnecting the waiting shell does not cancel the mutation.

The contract covers rename, add-file, add-declaration, add-implementation,
add-statement, and replace-declaration. Planning remains read-only and does not
require an idempotency key.

## Idempotency binding

The server binds an idempotency key atomically to a canonical fingerprint made
from the JSON-RPC method and normalized typed request payload after removing
the idempotency key. Repeating the same task, key, and fingerprint joins active
execution or returns the cached terminal result with `deduplicated` evidence.
Reusing the key for a different fingerprint is a typed conflict and never
starts another mutation.

The coordinator is authoritative for the lifetime of one selected runtime.
Before submission, the CLI persists the key and runtime identity in the active
task. If that runtime is replaced before a terminal response is observed, the
CLI records `SEMANTIC_MUTATION_OUTCOME_MISSING` and blocks the task rather than
replaying the mutation.

## Execution and serialization

One mutation lane serializes all applied mutations in a runtime workspace.
The coordinator retains only task/key-to-terminal-result deduplication, active
worker count, and the task finish barrier. No public polling, cancellation,
progress, tracing, path-scope, or queue contract remains.

Finish closes admission and drains active server-owned workers before
validation. Ctrl-C stops waiting only. Recovery reruns the exact command with
the same key against the same runtime, joining or retrieving its result.

On terminal success, the CLI clears the in-flight marker. On terminal failure,
it persists the typed blocker and rejects further mutations and `finish` until
`abort` followed by `begin` starts a new task.

## Filesystem fallback

An interrupted or failed applied mutation never authorizes automatic
filesystem fallback. Same-runtime recovery uses the same key; changed-runtime
ambiguity and terminal failure block the task for explicit abort and restart.

## Source of truth

| Layer | Owner |
| --- | --- |
| Terminal mutation result | `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/mutation/` |
| Serialized execution, deduplication, and finish barrier | `analysis-server/src/main/kotlin/io/github/amichne/kast/server/mutation/` |
| Public `--idempotency-key`, runtime binding, and task blockers | `cli-rs/src/cli/agent.rs`, `cli-rs/src/agent/` |
| Public recovery guidance | `docs/reference/agent-commands.md`, `docs/use/automate-with-agents.md`, `cli-rs/resources/kast-skill/` |

## Validation

```console
./gradlew :analysis-api:test :analysis-server:test
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_command_surface_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
git diff --check
```

The server tests must cover synchronous terminal responses, global
serialization, same-key joining and caching, different-payload conflict,
client-disconnect survival, and finish-barrier drain and rejection.

## Change rule

Further mutation-execution expansion must preserve atomic key binding, typed
terminal outcomes, plan-first `--apply` gating, and fail-closed runtime
replacement. Adding runtime-restart durability requires a superseding ADR that
owns persistence, retention, schema migration, and crash recovery.
