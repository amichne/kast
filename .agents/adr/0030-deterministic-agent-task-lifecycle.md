# ADR 0030: Deterministic agent task lifecycle and completion proof

Status: Accepted

Date: 2026-07-19

This ADR supersedes the task-workflow, provider-hook state, generated command
tutorial, agent-resource, and machine-resource portions of ADRs 0002, 0005,
0006, 0026, and 0029. It preserves their typed semantic CLI, plan-first
mutation, processless macOS machine, exact-root IDEA runtime, release, privacy,
and native provider trust decisions except where this record says otherwise.

## Context

Kast has compiler-backed semantic commands and an exact-root IDEA runtime
lease, but completion is still assembled by each agent provider. The Codex
adapter maintains a second baseline and diagnostics state machine, Copilot
injects a static command tutorial without a completion gate, and the generic
Gradle completion hook composed by downstream tooling guesses tasks from paths
and console output. None of those paths can prove that diagnostics, Gradle task
outcomes, test reports, and final file contents describe one unchanged input.

The IDEA runtime lease is deliberately tied to a live plugin process and its
caller. It must not be broadened into a portable task owner: provider callbacks
may arrive in different processes, and Linux release or CI work uses a
headless runtime. Task ownership and semantic-runtime ownership are different
facts and need different types.

## Decision

### One task lifecycle

`kast agent task begin|status|finish|abort` is the only completion lifecycle.
`kast agent` with no subcommand renders a compact TOON home view containing
readiness, the current task, and contextual next commands.

A task has exactly one of these persisted states:

- `ACTIVE`: admitted and collecting evidence;
- `VALIDATING`: a specific final-input digest is being checked;
- `COMPLETE`: validation succeeded and the completion receipt is immutable;
- `BLOCKED`: validation failed with typed retryable evidence; or
- `ABORTED`: owned task resources were released without a completion claim.

The task receipt binds a generated task ID, a task-lease ID, stable owner,
canonical exact root, effective generation, baseline and current relevant-file
hashes, diagnostics hashes, Gradle model and policy digests, selected tasks,
observed task outcomes, test-report digests, blockers, and timestamps. Strict
versioned records are written atomically under the exact worktree's Kast data
directory. Unknown fields, generation drift, root drift, owner conflict, and
malformed state fail closed.

Task ownership is a cross-platform, session-stable task lease. A non-empty
`KAST_AGENT_SESSION_ID` is authoritative, followed by `CODEX_THREAD_ID`; only
an interactive caller without a session identity falls back to process-start
identity. The process identity is liveness evidence, not a provider session's
durable identity. Repeated `begin` for the same live owner and exact root is
idempotent. Another live owner conflicts. Dead validating owners are reported
as recoverable blocked tasks rather than inferred successes.

This task lease never starts, borrows, validates, or stops a semantic runtime.
The existing `kast agent lease` remains an IDEA-only exact-root runtime lease
on macOS. Task validation invokes the platform's admitted semantic route for
diagnostics and the exact-root Gradle wrapper for build proof; those runtime
facts are recorded separately from task ownership.

`abort` changes only an owned non-terminal task to `ABORTED`. It never claims
completion. `finish` retains an `ACTIVE` or `BLOCKED` task on failure so the
same owner can repair and retry. Only successful completion releases the
active task claim.

### Relevant input and diagnostics proof

The task baseline contains all Git-tracked and non-ignored untracked relevant
paths and their typed present-or-missing content identity. Relevant paths are
Kotlin, Kotlin script, and Java sources; Gradle build and settings files;
wrapper files; version catalogs; and files below `gradle/` or `build-logic/`.
Pre-existing dirt remains baseline evidence and is not attributed to the task.
An unchanged relevant set produces an explicit no-op completion.

For every changed Kotlin source or script, diagnostics must be complete, have
zero errors, and carry the hash computed in the same backend read epoch as the
diagnostics. Hashing the disk after an IDEA analysis is not equivalent because
an open document may contain unsaved text. The diagnostics API therefore
includes required per-file hashes and retains them in paginated snapshots.

### Gradle model and proof

Kast invokes only the canonical exact-root `gradlew`. A bundled, generation-
attested init script emits a strict model of build roots, projects, source
sets, source roots, available task identities, typed test tasks, and report
locations. It also records structured completion events. Console parsing,
ambient `gradle`, arbitrary commands, and task-name-only test inference are
not proof.

Kast infers the owning compilation/build task and at least one owning test task
for unambiguous JVM source sets. Build, settings, wrapper, catalog, `gradle/`,
or `build-logic/` changes select affected build-level build/check and test
proof. Composite identities retain build root, project path, and task path.
KMP, Android, custom suites, incomplete composite models, or any other
ambiguous mapping fail with `GRADLE_VALIDATION_POLICY_REQUIRED` and a ready-to-
paste override.

The only override is exact-root `.kast/workflow.toml` with schema version 1:

```toml
schema_version = 1

[[gradle.validation]]
build_root = "."
project_path = ":app"
source_sets = ["main"]
build_tasks = [":app:classes"]
test_tasks = [":app:test"]
```

The parser rejects unknown fields, absolute or escaping build roots, invalid
project/task identities, duplicate equally specific matches, arbitrary shell
commands, missing tasks, and empty test requirements. Exact build root and
project match first; an optional source-set match is more specific.

`SUCCESS`, `UP_TO_DATE`, and `FROM_CACHE` are valid only for selected tasks
observed against the receipt's final-input digest. Failure, skipped,
`NO_SOURCE`, and unobserved tasks are blockers. Every selected test task must
be model-classified as a test, have an accepted outcome, and have a
deterministically hashed test report. Kast rehashes all relevant inputs after
validation; any drift returns `WORKSPACE_CHANGED_DURING_VALIDATION`.

### Provider adapters and public output

Provider adapters translate host events and envelopes only. They do not own
baselines, validation policy, task selection, or completion decisions.

Codex uses `SessionStart`, `PreToolUse`, `PostToolUse`, and `Stop` over the task
core. Its typed Kotlin mutation interception remains a guardrail. `Stop` calls
`finish` and uses the documented continuation response when proof is blocked.
Codex plugin hooks still require native user trust; setup and readiness report
`HOOK_TRUST_REQUIRED` and never bypass that flow.

The Copilot extension uses `onSessionStart`, `onPreToolUse`,
`onPostToolUse`, `onPostToolUseFailure`, and `onSessionEnd`. Its session-end
callback records the task finish result as audit evidence because it cannot
hard-block an already ending session. The independent shell lifecycle remains
the hard-gate path.

The companion `kast-agent-task` POSIX launcher contains no policy. It resolves
only an executable sibling `kast`, then forwards arguments and stdin to
`kast agent task`; it never searches `PATH`, parses output, or implements a
fallback. Every distribution installs and attests the launcher beside the CLI.

Public `kast agent` output defaults to current-spec TOON even on a terminal.
Explicit `--output json` remains byte-compatible during this migration and
emits one deprecation warning on stderr. Host-mandated JSON hook envelopes,
internal receipts, and internal Gradle records are exempt. First-party skills,
documentation, scripts, and examples do not request public JSON.

### Resource and generation authority

The active machine and packaged release generation include the CLI, launcher,
minimal provider-neutral skill and guidance, workflow schema, Gradle init
script, Codex adapter, Copilot adapter, and generated metadata. A resource-only
change therefore changes the effective generation. One strict resource-tree
digest is preferred to manifest fields for individual provider files.

The provider-neutral and Codex skills teach only when Kast applies, task begin,
command discovery through `kast agent` and scoped help, task finish, and typed
blocker reporting. Generated command tutorials and hand-maintained invocation
inventories are deleted.

Machine status, setup, readiness, and repair verify the selected resources
without silently changing them. macOS IDEA preparation, Homebrew CLI ownership,
JetBrains plugin ownership, and the prohibition on a local headless JVM remain
unchanged.

The downstream generic `gradle-check-green` composition is removed only after
this gate is available. Its primitive may remain for explicit non-Kast use;
Kast does not import or wrap it.

## Source ownership

| Contract | Owner | Focused validation |
| --- | --- | --- |
| Task state, baseline, policy, inference, proof | `cli-rs/src/agent/task.rs` | task lifecycle and Gradle proof smoke tests |
| Public task CLI and TOON home | `cli-rs/src/cli/agent.rs`, `cli-rs/src/agent/` | agent command/output smoke tests |
| Backend diagnostics hashes | `analysis-api/`, `backend-idea/`, `analysis-server/` | focused diagnostics tests |
| Launcher and task resources | `cli-rs/resources/agent-task/` | launcher and packaged-content tests |
| Codex translation and generation | `cli-rs/src/codex/`, `cli-rs/resources/codex-plugin/` | Codex generator and hook tests |
| Copilot translation | `cli-rs/resources/plugin/extensions/kast/` | Copilot plugin contract |
| Machine/release attestation | `cli-rs/src/machine.rs`, packaging and release owners | machine and packaging contracts |

## Validation

Changes to this contract run focused task, diagnostics, provider, machine, and
packaging tests first, followed by Rust format, clippy, and full tests; Gradle
module tests and `./gradlew test buildIdeaPlugin`; generated-contract checks;
release, installer, provider-package, documentation, and CI workflow-model
contracts. Acceptance must prove the open-IDE macOS generation separately from
the packaged Linux/headless generation rather than reviving the retired
developer-machine headless authority.
