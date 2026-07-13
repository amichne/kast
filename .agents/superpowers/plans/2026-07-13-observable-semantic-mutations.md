# Observable Semantic Mutations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. This issue explicitly forbids subagent delegation, so execution stays inline.

**Goal:** Give every public applied semantic mutation a stable idempotent operation lifecycle that remains queryable and cancellable after the submitting client disconnects.

**Architecture:** Add host-agnostic sealed mutation lifecycle contracts in `analysis-api`, then run typed mutation variants through an atomic daemon-resident registry in `analysis-server`. The Rust CLI keeps read-only plans unchanged, requires a caller key at the `--apply` boundary, submits work asynchronously, and exposes typed operation status and cancellation commands; authored catalog and guidance sources then regenerate their owned artifacts.

**Tech Stack:** Kotlin, kotlinx.serialization, kotlinx.coroutines, JUnit Jupiter, Gradle, Rust 2024, Clap, Serde, Cargo, Zensical.

## Global Constraints

- Cover rename, add-file, add-declaration, add-implementation, add-statement, and replace-declaration.
- A key binds atomically to the actual symbol method plus canonical normalized payload excluding the key.
- Same key and fingerprint returns one operation ID and retained state; another fingerprint returns typed conflict before mutation.
- Cancellation becomes terminal only after the worker cooperatively stops.
- Preserve edit-application started/completed facts through validation failure and cancellation.
- Status and cancel are idempotent and accept exactly one operation ID or idempotency-key selector.
- Retain typed terminal results for the backend daemon lifetime; daemon-restart durability remains unsupported and ambiguous.
- Filesystem fallback is safe only when successfully retrieved terminal state proves edit application never began.
- Keep one non-private top-level production Kotlin type per same-named file; direct sealed variants stay with their root.
- Treat `cli-rs/resources/kast-skill/references/commands.json` as catalog source and regenerate YAML/request schemas/samples.
- Do not push or open a pull request.

---

### Task 1: Add the host-agnostic lifecycle contract

**Files:**
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/mutation/KastMutationOperationId.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/mutation/KastMutationIdempotencyKey.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/mutation/KastSemanticMutationKind.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/mutation/KastSemanticMutation.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/mutation/KastMutationOperationSelector.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/mutation/KastMutationProgressStage.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/mutation/KastMutationEditApplicationState.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/mutation/KastMutationExecutionTrace.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/mutation/KastSemanticMutationResult.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/mutation/KastMutationFailure.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/mutation/KastMutationOperationState.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/mutation/KastMutationOperationSnapshot.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/mutation/KastMutationSubmissionReceipt.kt`
- Test: `analysis-api/src/test/kotlin/io/github/amichne/kast/api/contract/mutation/KastMutationContractTest.kt`

**Interfaces:**
- Produces a UUID-shaped serializable `KastMutationOperationId` and a
  serializable `KastMutationIdempotencyKey` whose trimmed length is 1 through
  128 characters.
- Produces `KastSemanticMutation` with nested `Rename`, `AddFile`, `AddDeclaration`, `AddImplementation`, `AddStatement`, and `ReplaceDeclaration` request variants.
- Produces `KastMutationOperationSelector.ByOperationId` and `.ByIdempotencyKey`.
- Produces a sealed state machine whose direct variants are `Queued`, `Applying`, `Validating`, `Completed`, `Failed`, and `Cancelled`.
- Produces typed `KastSemanticMutationResult` and `KastMutationFailure` variants rather than string-tagged payloads.

- [ ] **Step 1: Write failing serialization and invariant tests**

Add tests that construct all six mutation variants, serialize their stable type discriminators, round-trip both selectors, reject blank/oversized idempotency keys and malformed operation IDs, and assert that terminal states require a typed result, failure, or cancelled outcome.

Representative assertions:

```kotlin
val encoded = json.encodeToJsonElement(
    KastSemanticMutation.serializer(),
    KastSemanticMutation.AddFile(
        idempotencyKey = KastMutationIdempotencyKey("issue-333-add-file"),
        request = KastAddFileRequest(filePath = "/workspace/Added.kt", contentFile = "/tmp/Added.kt"),
    ),
).jsonObject
assertEquals(JsonPrimitive("ADD_FILE"), encoded["type"])
assertFailsWith<IllegalArgumentException> { KastMutationIdempotencyKey(" ") }
assertFailsWith<IllegalArgumentException> { KastMutationOperationId("not-a-uuid") }
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```console
./gradlew :analysis-api:test --tests io.github.amichne.kast.api.contract.mutation.KastMutationContractTest
```

Expected: test compilation fails because the mutation lifecycle package does not exist.

- [ ] **Step 3: Implement the minimal sealed contract**

Use serializable value classes and sealed roots. `KastMutationOperationState` carries a `KastMutationExecutionTrace` in every variant and no nullable terminal payload:

```kotlin
@Serializable
sealed interface KastMutationOperationState {
    val trace: KastMutationExecutionTrace
    val cancellationRequested: Boolean

    @Serializable @SerialName("QUEUED")
    data class Queued(
        override val trace: KastMutationExecutionTrace = KastMutationExecutionTrace(),
        override val cancellationRequested: Boolean = false,
    ) : KastMutationOperationState

    @Serializable @SerialName("COMPLETED")
    data class Completed(
        val result: KastSemanticMutationResult,
        override val trace: KastMutationExecutionTrace,
        override val cancellationRequested: Boolean,
    ) : KastMutationOperationState
}
```

`Applying` and `Validating` require the active progress stage plus a trace.
`Failed` requires a `KastMutationFailure`; `Cancelled` requires the last
truthful trace; and both retain `cancellationRequested`. No active variant can
carry a terminal result, and no terminal variant can omit its typed outcome.
`KastMutationExecutionTrace` owns entered stages and one enum value
`NOT_STARTED`, `STARTED`, or `COMPLETED`; it never exposes contradictory
booleans.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the same Gradle command. Expected: all `KastMutationContractTest` cases pass.

- [ ] **Step 5: Commit the contract slice**

```console
git add analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/mutation analysis-api/src/test/kotlin/io/github/amichne/kast/api/contract/mutation
git diff --cached --check
git commit -m "feat: model semantic mutation lifecycles"
```

### Task 2: Execute and retain operations in the backend daemon

**Files:**
- Create: `analysis-server/src/main/kotlin/io/github/amichne/kast/server/mutation/MutationFingerprint.kt`
- Create: `analysis-server/src/main/kotlin/io/github/amichne/kast/server/mutation/MutationProgressEvent.kt`
- Create: `analysis-server/src/main/kotlin/io/github/amichne/kast/server/mutation/MutationProgressReporter.kt`
- Create: `analysis-server/src/main/kotlin/io/github/amichne/kast/server/mutation/MutationOperationRegistry.kt`
- Create: `analysis-server/src/main/kotlin/io/github/amichne/kast/server/mutation/MutationOperationService.kt`
- Modify: `analysis-server/src/main/kotlin/io/github/amichne/kast/server/AnalysisDispatcher.kt`
- Modify: `analysis-server/src/main/kotlin/io/github/amichne/kast/server/SkillRpcOrchestrator.kt`
- Test: `analysis-server/src/test/kotlin/io/github/amichne/kast/server/mutation/MutationOperationRegistryTest.kt`
- Test: `analysis-server/src/test/kotlin/io/github/amichne/kast/server/mutation/MutationOperationLifecycleTest.kt`
- Modify: `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisServerSocketTest.kt`

**Interfaces:**
- `MutationOperationRegistry.submit(mutation, fingerprint, execute)` atomically returns one `KastMutationSubmissionReceipt` per key/fingerprint.
- `MutationOperationRegistry.status(selector)` and `.cancel(selector)` return the same immutable snapshot shape and are safe to retry.
- `MutationProgressReporter.report(event)` receives `StageEntered` and `EditApplicationCompleted` events.
- `MutationOperationService` canonicalizes decoded typed payloads, executes each mutation variant through `SkillRpcOrchestrator`, and converts reported failures and exceptions into typed terminal states.
- JSON-RPC methods are `mutation/submit`, `mutation/status`, and `mutation/cancel`.

- [ ] **Step 1: Write registry tests for atomic idempotency and truthful cancellation**

Use `CompletableDeferred` gates and an injected operation-ID factory. Tests prove:

```kotlin
val first = registry.submit(mutation, fingerprint, execute)
val retry = registry.submit(mutation, fingerprint, execute)
assertEquals(first.operation.operationId, retry.operation.operationId)
assertEquals(1, executionCount.get())
assertFailsWith<MutationIdempotencyConflictException> {
    registry.submit(otherMutationWithSameKey, otherFingerprint, execute)
}
```

Cancellation tests block before edit application and after
`EditApplicationCompleted`, call cancel twice, release or cancel the worker,
await terminal state, and assert `Cancelled.trace.editApplicationState` is
respectively `NOT_STARTED` or `COMPLETED`. The immediate cancel response may
remain active with `cancellationRequested=true`; terminal cancellation is
asserted only after the job completion callback runs.

- [ ] **Step 2: Run registry tests and verify RED**

```console
./gradlew :analysis-server:test --tests io.github.amichne.kast.server.mutation.MutationOperationRegistryTest
```

Expected: test compilation fails because the registry does not exist.

- [ ] **Step 3: Implement the atomic registry**

Confine mutable entries to a private nested registry type and guard both maps
and every state transition with one lock. Build the worker with a dispatcher-
owned `CoroutineScope(SupervisorJob() + Dispatchers.Default)`. Start it once
after the entry and job are installed. Register `invokeOnCompletion` so a
cancelled job transitions to terminal `Cancelled` only after cooperative
completion.

Canonical fingerprint creation recursively sorts JSON object keys and hashes:

```text
<symbol-method>\n<normalized-typed-payload-without-idempotencyKey>
```

with SHA-256. A different fingerprint for an existing key throws a typed
conflict before a job is created.

- [ ] **Step 4: Run registry tests and verify GREEN**

Run the same focused registry test. Expected: all idempotency, status,
cancellation, and retention cases pass.

- [ ] **Step 5: Write failing dispatcher/orchestrator lifecycle tests**

Add tests that submit an add-file and a rename, poll by both selectors, and
assert terminal typed results. A stage-gated backend verifies the retained
stage order:

```kotlin
listOf(
    KastMutationProgressStage.IDENTITY_RESOLUTION,
    KastMutationProgressStage.EDIT_APPLICATION,
    KastMutationProgressStage.WORKSPACE_REFRESH,
    KastMutationProgressStage.IMPORT_OPTIMIZATION,
    KastMutationProgressStage.DIAGNOSTICS,
)
```

File creation omits identity resolution. A delayed backend blocks one
operation for at least `10_100` milliseconds; submission must return in less
than one second, and a later status call must retrieve the terminal result.

- [ ] **Step 6: Run lifecycle tests and verify RED**

```console
./gradlew :analysis-server:test --tests io.github.amichne.kast.server.mutation.MutationOperationLifecycleTest
```

Expected: JSON-RPC methods are unknown and no progress reporter exists.

- [ ] **Step 7: Wire the service, RPC methods, and actual progress boundaries**

Add `mutation/submit`, `mutation/status`, and `mutation/cancel` to the
dispatcher. The service passes a reporter into every public mutation path.
`SkillRpcOrchestrator` reports identity resolution before named symbol/scope
resolution, edit application immediately before the backend edit, edit
completion immediately after it returns, explicit workspace refresh before
import optimization, and diagnostics before terminal response construction.
Call `ensureActive()` after recording edit completion so a cancellation that
arrived during a non-cooperative backend write retains `COMPLETED` before the
worker stops.

- [ ] **Step 8: Verify lifecycle GREEN and socket reconnect behavior**

Run the lifecycle test, then add and run a UDS test that submits without
reading the response, reconnects, queries by idempotency key, releases the
gated backend, and retrieves the terminal result:

```console
./gradlew :analysis-server:test --tests io.github.amichne.kast.server.mutation.MutationOperationLifecycleTest
./gradlew :analysis-server:test --tests io.github.amichne.kast.server.AnalysisServerSocketTest
```

Expected: both classes pass, including the greater-than-ten-second and
disconnect/reconnect scenarios.

- [ ] **Step 9: Commit the daemon lifecycle slice**

```console
git add analysis-server analysis-api
git diff --cached --check
git commit -m "feat: retain observable mutation operations"
```

### Task 3: Expose idempotent apply, status, and cancellation in the AXI CLI

**Files:**
- Modify: `cli-rs/src/cli/agent.rs`
- Modify: `cli-rs/src/agent.rs`
- Modify: `cli-rs/src/agent/dispatch.rs`
- Test: `cli-rs/tests/agent_command_surface_smoke.rs`
- Create: `cli-rs/tests/agent_operation_surface_smoke.rs`
- Modify: `cli-rs/resources/kast-skill/references/commands.json`
- Generate: `cli-rs/resources/kast-skill/references/commands.yaml`
- Generate: `cli-rs/resources/kast-skill/references/requests/mutation/submit/`
- Generate: `cli-rs/resources/kast-skill/references/requests/mutation/status/`
- Generate: `cli-rs/resources/kast-skill/references/requests/mutation/cancel/`
- Modify/Generate: `cli-rs/protocol/api-specification.md`

**Interfaces:**
- Every public mutation args type flattens `AgentMutationApplyArgs { apply, idempotency_key }`.
- `AgentCommand::Operation(AgentOperationArgs)` owns `Status` and `Cancel` subcommands.
- The apply path sends a typed `mutation/submit` variant; the plan path retains its original typed `symbol/*` request.
- Operation selector argument groups require exactly one `--operation-id` or `--idempotency-key`.

- [ ] **Step 1: Write failing CLI command and request-shape tests**

Extend the plan tests to prove plans remain read-only. Add tests that:

- run every mutation with `--apply` but without `--idempotency-key` and receive
  structured `AGENT_USAGE` before runtime discovery;
- parse `kast agent operation status|cancel` with each selector and reject zero
  or two selectors;
- use a fake Unix socket backend to capture the request and assert apply emits
  `mutation/submit` with the expected variant, key, and nested typed request;
- assert status and cancel retries return backend-provided snapshots unchanged.

- [ ] **Step 2: Run focused CLI tests and verify RED**

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_command_surface_smoke --test agent_operation_surface_smoke
```

Expected: `operation` is unknown and apply reaches runtime discovery instead
of rejecting the missing idempotency key.

- [ ] **Step 3: Implement the typed CLI boundary**

Add `AgentMutationApplyArgs` and require a nonblank key inside dispatch only
when `apply` is true. Build submission JSON in one owner:

```rust
json!({
    "type": mutation_kind,
    "idempotencyKey": idempotency_key,
    "request": mutation_params,
})
```

Use `mutation/submit`, `mutation/status`, and `mutation/cancel` only behind the
typed public commands. Keep progress off stdout and preserve the existing
structured `AgentEnvelope` output path for JSON and TOON.

- [ ] **Step 4: Verify CLI GREEN**

Run the same focused Cargo command. Expected: all plan, apply validation,
request capture, status, cancellation, and selector cases pass.

- [ ] **Step 5: Add catalog source entries and regenerate owned outputs**

Add a `mutation` category with the three lifecycle methods. Define top-level
variants for all six submit requests and both selector forms. Then run:

```console
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract
python3 .github/scripts/render-rpc-contract-summary.py --write
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check
```

Expected: YAML, schemas, samples, and generated summary match the authored JSON
catalog.

- [ ] **Step 6: Commit the CLI and generated contract slice**

```console
git add cli-rs/src cli-rs/tests cli-rs/resources/kast-skill/references cli-rs/protocol/api-specification.md
git diff --cached --check
git commit -m "feat: expose mutation operation controls"
```

### Task 4: Publish safe recovery and filesystem fallback guidance

**Files:**
- Modify: `docs/reference/agent-commands.md`
- Modify: `docs/reference/mutation-selectors.md`
- Modify: `docs/use/automate-with-agents.md`
- Modify: `cli-rs/resources/kast-skill/SKILL.md`
- Modify: `cli-rs/resources/kast-skill/references/quickstart.md`
- Test: `cli-rs/tests/packaged_content_smoke.rs`

**Interfaces:**
- Public docs teach the typed command surface, not raw lifecycle RPC methods.
- Packaged guidance requires a stable caller key, status before retry, and the
  exact fallback safety boundary.

- [ ] **Step 1: Add a failing packaged-content contract**

Assert the packaged skill includes `kast agent operation status`,
`--idempotency-key`, cancellation, and the rule that filesystem fallback is
unsafe after edit application began or when daemon-restart state is missing.

- [ ] **Step 2: Verify RED**

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test packaged_content_smoke
```

Expected: assertions fail because current skill guidance has no lifecycle or
fallback contract.

- [ ] **Step 3: Update authored public and packaged guidance**

Document this recovery sequence without exposing raw methods:

```console
kast agent add-file ... --apply --idempotency-key <stable-key> --workspace-root "$PWD"
kast agent operation status --idempotency-key <stable-key> --workspace-root "$PWD"
kast agent operation cancel --operation-id <operation-id> --workspace-root "$PWD"
```

State that filesystem fallback requires a retrieved terminal failed/cancelled
state with edit application `NOT_STARTED`; all active, completed,
edit-started, unreachable, and post-restart-unknown states are unsafe or
ambiguous.

- [ ] **Step 4: Verify docs and packaged guidance GREEN**

```console
cargo test --manifest-path cli-rs/Cargo.toml --locked --test packaged_content_smoke
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
```

- [ ] **Step 5: Commit the guidance slice**

```console
git add docs cli-rs/resources/kast-skill cli-rs/tests/packaged_content_smoke.rs
git diff --cached --check
git commit -m "docs: define safe mutation recovery"
```

### Task 5: Review, validate, report, and hand off the committed branch

**Files:**
- Modify: `.agent-turn/issue-333-report.md`
- Write: `.agent-turn/kotlin-agentic-correctness/<session>/scorecard.json`
- Write: `.agent-turn/kotlin-agentic-correctness/<session>/evidence.jsonl`

- [ ] **Step 1: Run Kotlin semantic diagnostics or retain the exact blocker**

```console
kast agent verify --workspace-root "$PWD"
```

If the plugin-owned isolated-worktree metadata remains unavailable, record
`MACOS_PLUGIN_WORKSPACE_REQUIRED` and use focused/full Gradle compilation as
the authoritative proof; do not run `kast setup`.

- [ ] **Step 2: Run focused and full Kotlin validation**

```console
./gradlew :analysis-api:test :analysis-server:test
./gradlew test
```

- [ ] **Step 3: Run full Rust and generated-contract validation**

```console
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
cargo test --manifest-path cli-rs/Cargo.toml --locked
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check
python3 .github/scripts/render-rpc-contract-summary.py --check
```

- [ ] **Step 4: Run documentation, package, and diff gates**

```console
.github/scripts/test-kast-copilot-plugin.sh
.github/scripts/test-lsp-pivot-gates.sh
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
git diff --check
```

- [ ] **Step 5: Review requirements and Kotlin scorecard**

Re-read issue #333, ADR 0015, and this plan. Self-review the complete diff for
double-apply races, cancellation timing, retained edit facts, selector
validation, public/raw boundary leakage, one-type-per-file compliance, and
generated ownership. Write the nine-dimension Kotlin Engineering scorecard;
no dimension may be `Fail`.

- [ ] **Step 6: Write the durable turn report**

Write `.agent-turn/issue-333-report.md` with status, commit SHAs, commands and
results, exact Kast blocker if present, acceptance-criterion evidence, and
remaining concerns. The report stays untracked as requested.

- [ ] **Step 7: Commit any final scoped corrections and verify clean handoff**

```console
git status --short --branch
git log --oneline origin/main..HEAD
git diff --stat origin/main...HEAD
```

Do not push or create a pull request. Preserve the isolated worktree and branch
for the parent agent's handoff.
