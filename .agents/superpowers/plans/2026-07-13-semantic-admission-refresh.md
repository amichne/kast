# Semantic Admission Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make focused refresh a bounded semantic-admission barrier and route Kast-created files through it before immediate diagnostics.

**Architecture:** `analysis-api` owns an invariant-checked four-stage admission ledger embedded in `RefreshResult`. `backend-idea` synchronously probes admission and retries only pending states for a bounded deadline, while `analysis-server` sequences mutation validation through refresh.

**Tech Stack:** Kotlin 2.3/JVM 21, kotlinx.serialization, IntelliJ Platform 2025.3 VFS/index/PSI/analysis APIs, kotlinx.coroutines, JUnit Jupiter, Gradle.

## Global Constraints

- Filesystem discovery, source-module ownership, index admission, and analysis availability remain separate typed states.
- Focused retry uses a 25 millisecond interval and a 1.5 second maximum bounded by the request budget.
- Removed files are terminal refresh results; unresolved existing files return issue #332 typed incomplete evidence.
- A complete focused refresh guarantees immediate diagnostics can analyze every admitted path.
- Existing clean focused refresh remains below one second.
- Production Kotlin uses one non-private top-level named type per same-named file.
- `analysis-api` stays host-agnostic and IDEA state stays in `backend-idea`.
- Generated protocol files are regenerated from Kotlin source owners.
- Work remains on `feature/issue-335-semantic-admission` without push or PR publication.

---

### Task 1: Host-Agnostic Admission Contract

**Files:**
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/FileSystemDiscoveryState.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/SourceModuleOwnershipState.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/IndexAdmissionState.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/AnalysisAvailabilityState.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/SemanticAdmissionStatus.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/RefreshResult.kt`
- Create: `analysis-api/src/test/kotlin/io/github/amichne/kast/api/RefreshResultTest.kt`
- Modify: `analysis-api/src/testFixtures/kotlin/io/github/amichne/kast/testing/FakeAnalysisBackend.kt`

**Interfaces:**
- Produces: `SemanticAdmissionStatus.admitted`, `.removed`, and `.incomplete` factories.
- Produces: `RefreshResult.focused(statuses, attemptCount, elapsedMillis)` and `RefreshResult.full()` factories with issue #332 requested/analyzed/skipped summary fields plus removed count.

- [ ] **Step 1: Write failing result-contract tests**

Add tests that construct admitted, removed, and pending statuses and assert
derived lists, counts, semantic outcome, progress, serialization, and invalid
state rejection. The pending case must embed
`FileAnalysisStatus.skipped(path, PENDING_INDEX, message)`.

- [ ] **Step 2: Verify the red state**

Run:

```bash
./gradlew :analysis-api:test --tests io.github.amichne.kast.api.RefreshResultTest
```

Expected: compilation fails because the admission types and factories do not
exist.

- [ ] **Step 3: Implement the minimal typed contract**

Create one enum per same-named file, add invariant-checked status factories,
and make `RefreshResult` derive all focused summary fields. Update the fake
backend to return admitted statuses for known files and removed statuses for
absent requested paths.

- [ ] **Step 4: Verify the focused API slice**

Run the Task 1 command again and then:

```bash
./gradlew :analysis-api:test :analysis-server:test
```

Expected: both commands finish with `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit the contract slice**

```bash
git add analysis-api
git diff --cached --check
git commit -m "feat: model semantic refresh admission"
```

---

### Task 2: IDEA Bounded Admission Barrier

**Files:**
- Create: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/IdeaSemanticAdmissionAwaiter.kt`
- Modify: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/KastPluginBackend.kt`
- Create: `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/IdeaSemanticAdmissionAwaiterTest.kt`
- Create: `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastSemanticAdmissionRefreshTest.kt`

**Interfaces:**
- Consumes: Task 1 admission factories and `RefreshResult.focused`.
- Produces: `IdeaSemanticAdmissionAwaiter.await(paths, probe)` with attempts and elapsed milliseconds.
- Produces: IDEA `refresh` results that admit, remove, or fail closed for every focused path.

- [ ] **Step 1: Write failing retry and integration tests**

The pure awaiter test must prove one-probe fast-path completion, pending-to-
admitted retry, and bounded persistent pending evidence. The IDEA integration
test must cover production and test source roots, moved old/new paths, deleted
paths, immediate diagnostics, and an already-admitted refresh below one second.

- [ ] **Step 2: Verify the red state**

Run:

```bash
./gradlew :backend-idea:test --tests io.github.amichne.kast.idea.IdeaSemanticAdmissionAwaiterTest --tests io.github.amichne.kast.idea.KastSemanticAdmissionRefreshTest
```

Expected: compilation fails because the awaiter and strengthened refresh
behavior do not exist.

- [ ] **Step 3: Implement the awaiter and IDEA probe**

Add a fast-path-first awaiter with injected monotonic clock and suspend pause.
In `KastPluginBackend`, synchronously refresh each NIO path and classify VFS,
source content, Kotlin `FileTypeIndex`, PSI, and a real Kotlin analysis session
in order. Retry only pending statuses and preserve process cancellation.

- [ ] **Step 4: Verify IDEA green and diagnostics compatibility**

Run the Task 2 command again and then:

```bash
./gradlew :backend-idea:test --tests io.github.amichne.kast.idea.KastDiagnosticsCompletenessTest
./gradlew :backend-idea:test
```

Expected: all commands finish with `BUILD SUCCESSFUL`; the latency assertion is
below one second.

- [ ] **Step 5: Commit the IDEA slice**

```bash
git add backend-idea
git diff --cached --check
git commit -m "fix: await IDEA semantic admission"
```

---

### Task 3: Mutation Sequencing and Persistent Failure

**Files:**
- Modify: `analysis-server/src/main/kotlin/io/github/amichne/kast/server/SkillRpcOrchestrator.kt`
- Modify: `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt`
- Modify: `cli-rs/src/agent/types.rs`
- Modify: `cli-rs/src/agent/dispatch.rs`
- Modify: `cli-rs/tests/agent_diagnostics_smoke.rs`

**Interfaces:**
- Consumes: `backend.refresh(RefreshQuery(filePaths).parsed())` before optimize-imports and diagnostics.
- Produces: ordered mutation operations `apply -> refresh -> optimize -> diagnostics`.

- [ ] **Step 1: Write a failing mutation-order regression test**

Wrap the fake backend with an operation recorder. Dispatch a Kast create-file
request and assert that focused refresh receives the created path before import
optimization and diagnostics. Add an incomplete-refresh case that remains a
typed non-clean mutation.

- [ ] **Step 2: Verify the red state**

Run:

```bash
./gradlew :analysis-server:test --tests 'io.github.amichne.kast.server.AnalysisDispatcherTest.symbol add file awaits semantic admission before diagnostics'
```

Expected: the sequence lacks `refresh` before implementation.

- [ ] **Step 3: Add the refresh barrier to mutation paths**

Introduce one private `refreshFiles` helper, require the refresh capability,
and call it after create/edit/rename application but before optimization or
diagnostics. Keep delete handling terminal and avoid duplicate refresh calls in
one mutation path.

- [ ] **Step 4: Verify server and CLI fail-closed behavior**

Run:

```bash
./gradlew :analysis-server:test
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_diagnostics_smoke
```

Expected: Gradle and Cargo pass. Modify Rust only if the new incomplete refresh
payload would otherwise leave the refresh step successful and contradict the
#332 command contract.

- [ ] **Step 5: Commit the orchestration slice**

```bash
git add analysis-server cli-rs/src/agent/types.rs cli-rs/tests/agent_diagnostics_smoke.rs
git diff --cached --check
git commit -m "fix: refresh Kast mutations before analysis"
```

---

### Task 4: Generated Contracts and Full Verification

**Files:**
- Modify generated files under `cli-rs/protocol/` and `cli-rs/resources/kast-skill/references/` only through their owning Gradle generators.
- Create: `.agent-turn/issue-335-report.md` as untracked turn evidence.

**Interfaces:**
- Consumes: final serialized `RefreshResult` model and `OperationDoc` wording.
- Produces: drift-free OpenAPI, protocol Markdown, examples, and command catalog.

- [ ] **Step 1: Regenerate source-owned contracts**

Run:

```bash
./gradlew :analysis-api:generateOpenApiSpec :analysis-api:generateDocPages :analysis-server:generateDocExamples
```

These tasks own OpenAPI, protocol Markdown, and examples. The internal command
catalog names only the response type and does not change for additive fields.
Do not hand-edit generated outputs.

- [ ] **Step 2: Run focused and full gates**

Run:

```bash
./gradlew :analysis-api:test :analysis-server:test :backend-idea:test
./gradlew test
cargo test --manifest-path cli-rs/Cargo.toml --locked
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
git diff --check
```

Expected: every command exits zero. Record the Kast workspace verification
failure caused by absent plugin metadata as a concern, not as semantic proof.

- [ ] **Step 3: Review and commit generated outputs**

```bash
git diff --stat
git diff --check
git add cli-rs/protocol cli-rs/resources/kast-skill/references
git diff --cached --check
git commit -m "docs: regenerate semantic admission contracts"
```

- [ ] **Step 4: Write final turn evidence**

Write `.agent-turn/issue-335-report.md` with branch, commits, acceptance mapping,
red/green evidence, full command results, latency evidence, scorecard, and the
Kast plugin-metadata concern. Remove generated `.kotlin/` runtime output and
confirm the tracked worktree is clean.
