# Observable Semantic Mutations Design

**Status:** Approved

**Date:** 2026-07-13

## Purpose

Make every public applied semantic mutation recoverable when the invoking
agent process yields or disconnects before the mutation finishes. A caller
must be able to distinguish latency from completion, failure, and cancellation
without risking a duplicate edit.

The design covers all current public `--apply` mutations: rename, add-file,
add-declaration, add-implementation, add-statement, and replace-declaration.
Read-only planning remains unchanged.

## Considered approaches

### Server-owned asynchronous operation registry

The selected approach submits a typed mutation to the long-lived backend,
atomically binds its idempotency key, and returns an operation receipt before
execution proceeds. The backend owns execution, progress, cancellation, and
terminal-result retention. Any later CLI process can reconnect to the same
daemon and query by operation ID or idempotency key.

This is the only option that decouples mutation lifetime from both the CLI
process and the original transport connection while keeping compiler-backed
execution in its existing owner.

### CLI-owned background process and state files

This approach would fork a CLI worker and persist state locally. It was
rejected because shell yield, client death, and process supervision become
part of the correctness boundary, while backend lifecycle and IDEA write
access remain elsewhere.

### Synchronous mutation plus a progress ledger

This approach would record progress while retaining the current blocking RPC.
It was rejected because request timeout and connection lifetime would still
control execution. A disconnected caller could observe a ledger only if the
original request coroutine survived, which is the failure this issue removes.

## Trusted domain model

The untrusted boundaries are CLI strings and JSON-RPC payloads. They parse
once into constrained operation IDs, constrained idempotency keys, typed
mutation variants, typed operation selectors, lifecycle states, progress
stages, and terminal outcomes.

The shared contract owns:

- `KastMutationOperationId` and `KastMutationIdempotencyKey` value types;
- a sealed public mutation request variant for each applied command;
- operation selectors by ID or idempotency key;
- `KastMutationOperationStatus`: queued, applying, validating, completed,
  failed, or cancelled;
- `KastMutationProgressStage`: identity resolution, edit application,
  workspace refresh, import optimization, or diagnostics;
- an immutable operation snapshot containing both identifiers, mutation kind,
  status, current progress, cancellation request state, and edit-application
  facts;
- a submission receipt; and
- sealed completed, failed, and cancelled terminal outcomes carrying the
  typed mutation result or error.

Each non-private production Kotlin type gets a same-named file. Direct sealed
variants remain with their root. Raw strings and nullable flag combinations do
not cross from transport parsing into the operation registry.

## Submission and idempotency

Applied command requests include a required caller-chosen idempotency key. The
CLI requires the key only with `--apply`; mutation planning remains usable
without it and shows the key placeholder in the apply command.

The server normalizes the decoded request, excludes the idempotency key, and
hashes the JSON-RPC method plus canonical typed payload. Under one registry
lock it either:

1. returns the existing receipt when key and fingerprint match;
2. returns a typed conflict when the key already names another fingerprint; or
3. creates exactly one operation ID, stores the queued entry, and schedules
   exactly one worker.

The receipt returned for a retry contains the original operation ID. A retry
after completion returns retained state rather than starting another edit.
Different JSON object ordering cannot alter the fingerprint because hashing
uses the server's normalized typed serialization.

## Execution and progress

The dispatcher owns a supervisor coroutine scope independent of request
coroutines. Submission schedules work in that scope and returns immediately,
so the normal request timeout applies only to submission, status, and cancel
calls—not to mutation execution.

The worker publishes stage entry before invoking each stage. Lifecycle status
is `applying` through identity resolution and edit application, then
`validating` through workspace refresh, import optimization, and diagnostics.
The registry separately records `editApplicationBegan` and
`editApplicationCompleted`.

The semantic orchestrator reports progress at the actual boundaries:

- resolve a named symbol or placement identity;
- calculate and apply the edit plan;
- refresh affected workspace files;
- optimize imports when the mutation supports it; and
- retrieve diagnostics and build the terminal result.

File creation has no declaration identity to resolve, so its first entered
stage is edit application. Stages are not fabricated for work that does not
occur.

## Cancellation

Cancellation is idempotent and request based. The first request records
`cancellationRequested=true` and signals the worker job. Later requests return
the same evolving or terminal snapshot.

The operation becomes terminal cancelled only when the worker has
cooperatively stopped and the registry has recorded its last truthful stage.
If cancellation arrives before edit application begins, the terminal outcome
proves no semantic edit started. If edit application began or completed, those
facts remain true in the cancelled outcome. If a non-cancellable backend call
finishes despite the request, execution observes cancellation at the next
cooperative boundary and never rewrites history to claim the mutation did not
run.

Cancellation after any terminal state is a successful no-op returning the
same terminal state and result.

## Status and result retrieval

The CLI adds:

```console
kast agent operation status --operation-id <id> --workspace-root "$PWD"
kast agent operation status --idempotency-key <key> --workspace-root "$PWD"
kast agent operation cancel --operation-id <id> --workspace-root "$PWD"
```

Each command accepts exactly one selector. Status includes the retained typed
terminal outcome when present, so a reconnecting caller needs no hidden raw
RPC or separate result command. Both status and cancel are read-safe to retry.

The registry retains state only for the life of the backend daemon. Persistence
across daemon restart is deliberately out of scope; a lost registry is an
ambiguous outcome, not permission to retry a filesystem edit.

## Failure behavior

Expected failures are typed and observable:

- an unknown operation selector returns not found without mutation;
- a reused idempotency key with another fingerprint returns conflict without
  mutation;
- a mutation response with `ok=false` becomes a failed operation with its
  typed mutation response retained;
- an exception becomes a failed terminal outcome with a structured API error;
  and
- cooperative cancellation becomes a cancelled terminal outcome with stage
  and edit-application facts.

Internal raw edit methods remain transport implementation details. The public
agent mutation surface is the lifecycle contract governed here.

## Filesystem fallback

Filesystem fallback is safe only after a successful status query proves
`editApplicationBegan=false` in a terminal failed or cancelled outcome. It is
unsafe when:

- the operation completed;
- edit application began, even if validation later failed or cancellation was
  requested;
- the operation is still queued, applying, or validating;
- the daemon is unreachable; or
- the daemon restarted and no longer knows the operation.

In unsafe or ambiguous states the caller first recovers backend visibility,
retrieves the operation result when available, and inspects the workspace.

## Testing

Development follows red-green TDD in vertical slices:

1. shared serialization and invariant tests for IDs, selectors, lifecycle,
   stages, and terminal outcomes;
2. registry tests for atomic same-key retry, different-payload conflict,
   truthful cancellation, status idempotence, and result retention;
3. dispatcher/orchestrator tests for all five progress stages and typed
   mutation results;
4. a real operation delayed beyond ten seconds whose submission returns
   immediately and whose terminal result is retrieved later;
5. a Unix-domain-socket test that disconnects after submission, reconnects,
   and queries by idempotency key; and
6. Rust CLI tests for required apply keys, typed request shape, status/cancel
   selectors, structured output, and help.

Focused analysis API, analysis server, and CLI tests run throughout. Final
verification includes full Gradle and Cargo suites, Clippy, formatting,
contract generation checks, documentation contracts, Zensical rendering, and
diff hygiene. Kast semantic diagnostics are attempted first; in this isolated
worktree the plugin-owned workspace metadata may be unavailable, in which case
the exact failure is retained and Gradle compilation is authoritative.
