# Fail-Closed Semantic Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every diagnostics response prove per-file semantic completeness and make incomplete evidence fail the typed CLI with exit status one.

**Architecture:** `analysis-api` owns an invariant-checked per-file ledger and derived semantic outcome. The IDEA backend classifies each requested path and preserves ordinary compiler diagnostics, the server propagates completeness into mutation summaries, and the Rust response boundary converts incomplete semantic results into failed agent commands while promoting compact counts.

**Tech Stack:** Kotlin 2.3/JVM 21, kotlinx.serialization, IntelliJ Platform analysis APIs, Rust 2024, serde/serde_json, Clap, JUnit Jupiter, Gradle, Cargo.

## Global Constraints

- Every requested file has one of `ANALYZED`, `PENDING_INDEX`, `OUTSIDE_SOURCE_MODULES`, `MISSING_ON_DISK`, or `BACKEND_FAILURE`.
- Only skipped files or `ANALYSIS_FAILURE` diagnostics make semantic completeness fail; ordinary Kotlin compiler diagnostics remain analyzed evidence.
- Runtime readiness and JSON-RPC transport success remain separate from semantic completeness.
- Human, JSON, and TOON expose requested, analyzed, and skipped counts without parsing diagnostic messages.
- Production Kotlin keeps one non-private top-level named type per same-named file.
- `analysis-api` stays host-agnostic; IDEA PSI and indexing logic stays in `backend-idea`.
- The public command surface and protocol schema version remain unchanged; generated protocol artifacts come from their source owners.
- Use the isolated branch `feature/issue-332-fail-closed-analysis`; preserve unrelated worktree state.

---

### Task 1: Host-Agnostic Completeness Contract

**Files:**
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/FileAnalysisState.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/FileAnalysisStatus.kt`
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/SemanticAnalysisOutcome.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/result/DiagnosticsResult.kt`
- Create: `analysis-api/src/test/kotlin/io/github/amichne/kast/api/DiagnosticsResultTest.kt`
- Modify: `analysis-api/src/testFixtures/kotlin/io/github/amichne/kast/testing/FakeAnalysisBackend.kt`
- Modify: `analysis-api/src/testFixtures/kotlin/io/github/amichne/kast/testing/AnalysisBackendContractFixture.kt`
- Modify: `analysis-api/src/test/kotlin/io/github/amichne/kast/api/DocFieldCoverageTest.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/docs/OpenApiDocument.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/docs/DocsDocument.kt`

**Interfaces:**
- Consumes: `NormalizedPath`, `Diagnostic`, `PageableResult<Diagnostic>`, and parsed non-empty diagnostics paths.
- Produces: `FileAnalysisStatus.analyzed(NormalizedPath)`, `FileAnalysisStatus.skipped(NormalizedPath, FileAnalysisState, String)`, and `DiagnosticsResult.of(List<Diagnostic>, List<FileAnalysisStatus>, PageInfo?)`.

- [ ] **Step 1: Write failing completeness-contract tests**

Create `DiagnosticsResultTest.kt` with these tests:

```kotlin
class DiagnosticsResultTest {
    private val first = NormalizedPath.ofAbsolute(Path.of("/workspace/First.kt"))
    private val second = NormalizedPath.ofAbsolute(Path.of("/workspace/Second.kt"))

    @Test
    fun `all analyzed files produce complete counts`() {
        val result = DiagnosticsResult.of(
            diagnostics = emptyList(),
            fileStatuses = listOf(
                FileAnalysisStatus.analyzed(first),
                FileAnalysisStatus.analyzed(second),
            ),
        )

        assertEquals(SemanticAnalysisOutcome.COMPLETE, result.semanticOutcome)
        assertEquals(2, result.requestedFileCount)
        assertEquals(2, result.analyzedFileCount)
        assertEquals(0, result.skippedFileCount)
    }

    @Test
    fun `a skipped file produces an incomplete result`() {
        val result = DiagnosticsResult.of(
            diagnostics = listOf(analysisFailure(second.value, "File not found")),
            fileStatuses = listOf(
                FileAnalysisStatus.analyzed(first),
                FileAnalysisStatus.skipped(second, FileAnalysisState.MISSING_ON_DISK, "File not found"),
            ),
        )

        assertEquals(SemanticAnalysisOutcome.INCOMPLETE, result.semanticOutcome)
        assertEquals(1, result.analyzedFileCount)
        assertEquals(1, result.skippedFileCount)
    }

    @Test
    fun `analysis failure cannot produce a complete result`() {
        val result = DiagnosticsResult.of(
            diagnostics = listOf(analysisFailure(first.value, "backend failed")),
            fileStatuses = listOf(FileAnalysisStatus.analyzed(first)),
        )

        assertEquals(SemanticAnalysisOutcome.INCOMPLETE, result.semanticOutcome)
    }

    private fun analysisFailure(filePath: String, message: String): Diagnostic = Diagnostic(
        location = Location(
            filePath = filePath,
            startOffset = 0,
            endOffset = 0,
            startLine = 0,
            startColumn = 0,
            preview = "",
        ),
        severity = DiagnosticSeverity.ERROR,
        message = message,
        code = "ANALYSIS_FAILURE",
    )
}
```

Add shared-contract assertions that both the clean fixture and the syntactically broken fixture receive `ANALYZED`, with complete counts. The broken fixture must still contain ordinary compiler diagnostics rather than `ANALYSIS_FAILURE`.

- [ ] **Step 2: Run the tests and verify the red state**

Run:

```bash
./gradlew :analysis-api:test --tests io.github.amichne.kast.api.DiagnosticsResultTest --tests io.github.amichne.kast.testing.FakeAnalysisBackendContractTest
```

Expected: compilation fails because `FileAnalysisState`, `FileAnalysisStatus`, `SemanticAnalysisOutcome`, and `DiagnosticsResult.of` do not exist.

- [ ] **Step 3: Add the typed result model**

Create the three same-named production files. The core shapes are:

```kotlin
@Serializable
enum class FileAnalysisState {
    ANALYZED,
    PENDING_INDEX,
    OUTSIDE_SOURCE_MODULES,
    MISSING_ON_DISK,
    BACKEND_FAILURE,
}
```

```kotlin
@Serializable
data class FileAnalysisStatus private constructor(
    @DocField(description = "Normalized absolute path requested for semantic analysis.")
    val filePath: String,
    @DocField(description = "Typed semantic terminal state for the requested file.")
    val state: FileAnalysisState,
    @DocField(description = "Explanation when the file was not analyzed.", defaultValue = "null")
    val message: String? = null,
) {
    companion object {
        fun analyzed(filePath: NormalizedPath): FileAnalysisStatus =
            FileAnalysisStatus(filePath.value, FileAnalysisState.ANALYZED)

        fun skipped(
            filePath: NormalizedPath,
            state: FileAnalysisState,
            message: String,
        ): FileAnalysisStatus {
            require(state != FileAnalysisState.ANALYZED)
            require(message.isNotBlank())
            return FileAnalysisStatus(filePath.value, state, message)
        }
    }
}
```

```kotlin
@Serializable
enum class SemanticAnalysisOutcome {
    COMPLETE,
    INCOMPLETE,
}
```

Replace direct construction of `DiagnosticsResult` with a private constructor plus `of`. `of` counts analyzed states, treats every other state as skipped, and also sets `INCOMPLETE` when any diagnostic code is `ANALYSIS_FAILURE`. Its `init` block verifies all derived counts and outcome facts. `withItems` constructs a new result with the same ledger and summary so pagination cannot erase completeness evidence.

- [ ] **Step 4: Make the fake backend satisfy the stronger contract**

Change `FakeAnalysisBackend.diagnostics` to:

```kotlin
override suspend fun diagnostics(query: ParsedDiagnosticsQuery): DiagnosticsResult {
    val filePaths = query.filePaths.value
    filePaths.forEach { requireKnownFile(it.value) }
    return DiagnosticsResult.of(
        diagnostics = filePaths
            .flatMap { filePath -> diagnosticsByFile[filePath.value].orEmpty() }
            .sortedWith(compareBy({ it.location.filePath }, { it.location.startOffset })),
        fileStatuses = filePaths.map(FileAnalysisStatus::analyzed),
    )
}
```

Register `FileAnalysisStatus` with the OpenAPI/document registries and `DocFieldCoverageTest`. Enums stay inline with existing enum handling.

- [ ] **Step 5: Run the focused API tests and verify green**

Run the Task 1 Gradle command again.

Expected: `BUILD SUCCESSFUL`; clean and compiler-error fixtures are both semantically complete.

- [ ] **Step 6: Commit the contract slice**

```bash
git add analysis-api
git diff --cached --check
git commit -m "feat: model per-file semantic completeness"
```

---

### Task 2: IDEA Per-File Classification

**Files:**
- Modify: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/KastPluginBackend.kt:828`
- Create: `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/KastDiagnosticsCompletenessTest.kt`

**Interfaces:**
- Consumes: Task 1 `FileAnalysisStatus` factories and `DiagnosticsResult.of`.
- Produces: one ordered `FileAnalysisStatus` and zero or more diagnostics for each `ParsedDiagnosticsQuery.filePaths` entry.

- [ ] **Step 1: Write failing IDEA regression tests**

Use `@TestApplication`, `projectFixture`, `moduleFixture`, `sourceRootFixture`, and `psiFileFixture`. Add these scenarios:

```kotlin
@Test
fun `ordinary compiler diagnostics are analyzed evidence`() = runBlocking {
    ensureProjectReady()
    val filePath = brokenFileFixture.get().virtualFile.path

    val result = backend().diagnostics(DiagnosticsQuery(listOf(filePath)))

    assertEquals(SemanticAnalysisOutcome.COMPLETE, result.semanticOutcome)
    assertEquals(FileAnalysisState.ANALYZED, result.fileStatuses.single().state)
    assertTrue(result.diagnostics.isNotEmpty())
    assertTrue(result.diagnostics.none { it.code == "ANALYSIS_FAILURE" })
}

@Test
fun `missing file is explicit incomplete evidence`() = runBlocking {
    ensureProjectReady()
    val missing = sourceRoot.resolve("Missing.kt").toString()

    val result = backend().diagnostics(DiagnosticsQuery(listOf(missing)))

    assertEquals(SemanticAnalysisOutcome.INCOMPLETE, result.semanticOutcome)
    assertEquals(FileAnalysisState.MISSING_ON_DISK, result.fileStatuses.single().state)
    assertEquals(0, result.analyzedFileCount)
    assertEquals(1, result.skippedFileCount)
    assertEquals("ANALYSIS_FAILURE", result.diagnostics.single().code)
}
```

Also create an existing Kotlin file outside the source root and assert `OUTSIDE_SOURCE_MODULES`.
Create a non-Kotlin file inside the source root and assert `BACKEND_FAILURE`.

- [ ] **Step 2: Run the IDEA tests and verify the red state**

Run:

```bash
./gradlew :backend-idea:test --tests io.github.amichne.kast.idea.KastDiagnosticsCompletenessTest
```

Expected: missing-file expectations fail because the current backend only returns a synthetic diagnostic and no file ledger.

- [ ] **Step 3: Implement per-file analysis classification**

Add a tightly owned private nested result:

```kotlin
private data class DiagnosticsFileAnalysis(
    val status: FileAnalysisStatus,
    val diagnostics: List<Diagnostic>,
)
```

Split `diagnostics` into ordered concurrent calls to `analyzeDiagnosticsFile(NormalizedPath)`. The helper must:

```kotlin
private suspend fun analyzeDiagnosticsFile(filePath: NormalizedPath): DiagnosticsFileAnalysis {
    if (Files.notExists(filePath.toJavaPath())) {
        return skippedDiagnostics(filePath, FileAnalysisState.MISSING_ON_DISK, "File not found: ${filePath.value}")
    }
    return try {
        timedReadAction(telemetry, IdeaTelemetryScope.DIAGNOSTICS, "kast.idea.diagnostics.file") {
            if (!isWorkspaceFile(filePath.value)) {
                return@timedReadAction skippedDiagnostics(filePath, FileAnalysisState.OUTSIDE_SOURCE_MODULES, "File is outside the active workspace: ${filePath.value}")
            }
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath.value)
                ?: return@timedReadAction skippedDiagnostics(filePath, FileAnalysisState.PENDING_INDEX, "File is not admitted to the IDEA virtual filesystem: ${filePath.value}")
            if (!ProjectFileIndex.getInstance(project).isInSourceContent(virtualFile)) {
                return@timedReadAction skippedDiagnostics(filePath, FileAnalysisState.OUTSIDE_SOURCE_MODULES, "File is outside IDEA source modules: ${filePath.value}")
            }
            if (DumbService.isDumb(project)) {
                return@timedReadAction skippedDiagnostics(filePath, FileAnalysisState.PENDING_INDEX, "IDEA indexing is in progress: ${filePath.value}")
            }
            val file = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
                ?: return@timedReadAction skippedDiagnostics(filePath, FileAnalysisState.BACKEND_FAILURE, "Cannot resolve Kotlin PSI: ${filePath.value}")
            val diagnostics = analyze(file) {
                file.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
                    .flatMap { it.toApiDiagnostics() }
            }
            DiagnosticsFileAnalysis(FileAnalysisStatus.analyzed(filePath), diagnostics)
        }
    } catch (error: ProcessCanceledException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        skippedDiagnostics(filePath, FileAnalysisState.BACKEND_FAILURE, error.message ?: error.toString())
    }
}
```

`skippedDiagnostics` returns the typed skipped status and one zero-offset `ANALYSIS_FAILURE` diagnostic. The outer method flattens diagnostics, preserves sorted diagnostic order, preserves request order for statuses, and calls `DiagnosticsResult.of`.

- [ ] **Step 4: Run IDEA and API tests**

Run:

```bash
./gradlew :backend-idea:test --tests io.github.amichne.kast.idea.KastDiagnosticsCompletenessTest :analysis-api:test
```

Expected: `BUILD SUCCESSFUL` and every test scenario has exactly one typed state per request.

- [ ] **Step 5: Commit the backend slice**

```bash
git add backend-idea
git diff --cached --check
git commit -m "fix: classify incomplete IDEA diagnostics"
```

---

### Task 3: Server and Mutation Completeness Propagation

**Files:**
- Create: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/KastDiagnosticsSummary.kt`
- Modify: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/skill/SkillContracts.kt:686`
- Modify: `analysis-server/src/main/kotlin/io/github/amichne/kast/server/SkillRpcOrchestrator.kt:579,878,1241`
- Modify: `analysis-server/src/test/kotlin/io/github/amichne/kast/server/AnalysisDispatcherTest.kt`

**Interfaces:**
- Consumes: Task 1 `DiagnosticsResult.semanticOutcome` and file counts.
- Produces: `KastDiagnosticsSummary.from(DiagnosticsResult)` and `KastDiagnosticsSummary.completeWithoutFiles()`; mutation `clean` is false for incomplete evidence.

- [ ] **Step 1: Write failing server tests**

Add a raw dispatch assertion that `raw/diagnostics` returns complete counts for a known file. Add an `IncompleteDiagnosticsBackend` test delegate:

```kotlin
private class IncompleteDiagnosticsBackend(
    private val delegate: AnalysisBackend,
) : AnalysisBackend by delegate {
    override suspend fun diagnostics(query: ParsedDiagnosticsQuery): DiagnosticsResult {
        val filePath = query.filePaths.value.single()
        return DiagnosticsResult.of(
            diagnostics = listOf(analysisFailure(filePath.value, "File not found")),
            fileStatuses = listOf(
                FileAnalysisStatus.skipped(filePath, FileAnalysisState.MISSING_ON_DISK, "File not found"),
            ),
        )
    }
}
```

Dispatch `symbol/rename` or `symbol/write-and-validate` through that backend and assert the returned success-shape response has `ok == false`, `diagnostics.semanticOutcome == INCOMPLETE`, `analyzedFileCount == 0`, and `skippedFileCount == 1`.

- [ ] **Step 2: Run server tests and verify the red state**

Run:

```bash
./gradlew :analysis-server:test --tests io.github.amichne.kast.server.AnalysisDispatcherTest
```

Expected: compilation fails because `KastDiagnosticsSummary` does not expose completeness evidence.

- [ ] **Step 3: Move and strengthen the summary type**

Move `KastDiagnosticsSummary` out of `SkillContracts.kt` into its same-named file and add:

```kotlin
@Serializable
data class KastDiagnosticsSummary private constructor(
    val clean: Boolean,
    val semanticOutcome: SemanticAnalysisOutcome,
    val requestedFileCount: Int,
    val analyzedFileCount: Int,
    val skippedFileCount: Int,
    val errorCount: Int,
    val warningCount: Int,
    val errors: List<Diagnostic> = emptyList(),
) {
    companion object {
        fun from(result: DiagnosticsResult): KastDiagnosticsSummary {
            val errors = result.diagnostics.filter { it.severity == DiagnosticSeverity.ERROR }
            return KastDiagnosticsSummary(
                clean = result.semanticOutcome == SemanticAnalysisOutcome.COMPLETE && errors.isEmpty(),
                semanticOutcome = result.semanticOutcome,
                requestedFileCount = result.requestedFileCount,
                analyzedFileCount = result.analyzedFileCount,
                skippedFileCount = result.skippedFileCount,
                errorCount = errors.size,
                warningCount = result.diagnostics.count { it.severity == DiagnosticSeverity.WARNING },
                errors = errors,
            )
        }

        fun completeWithoutFiles(): KastDiagnosticsSummary = KastDiagnosticsSummary(
            clean = true,
            semanticOutcome = SemanticAnalysisOutcome.COMPLETE,
            requestedFileCount = 0,
            analyzedFileCount = 0,
            skippedFileCount = 0,
            errorCount = 0,
            warningCount = 0,
        )
    }
}
```

Replace manual summary construction and the private server `diagnosticsSummary` function with these factories.

- [ ] **Step 4: Run API and server tests**

Run:

```bash
./gradlew :analysis-api:test :analysis-server:test
```

Expected: `BUILD SUCCESSFUL`; pagination preserves counts and incomplete mutation validation is never clean.

- [ ] **Step 5: Commit the propagation slice**

```bash
git add analysis-api analysis-server
git diff --cached --check
git commit -m "fix: propagate semantic completeness through validation"
```

---

### Task 4: Rust Fail-Closed Agent Boundary and Output Summary

**Files:**
- Modify: `cli-rs/src/agent/response.rs`
- Modify: `cli-rs/src/agent/dispatch.rs:437`
- Create: `cli-rs/tests/agent_diagnostics_smoke.rs`

**Interfaces:**
- Consumes: JSON fields `semanticOutcome`, `requestedFileCount`, `analyzedFileCount`, `skippedFileCount`, and `diagnostics[].code`.
- Produces: `SEMANTIC_ANALYSIS_INCOMPLETE` or `SEMANTIC_ANALYSIS_FAILED`, failed step/command envelopes, exit status one, and command-level `semanticAnalysis`.

- [ ] **Step 1: Write the fake-daemon regression test**

Create a fake IDEA descriptor/socket server that answers, in order as requested, `runtime/status`, `capabilities`, `raw/workspace-refresh`, and `raw/diagnostics`. Refresh returns success. Diagnostics returns:

```json
{
  "diagnostics": [{
    "location": {"filePath":"/workspace/Missing.kt","startOffset":0,"endOffset":0,"startLine":0,"startColumn":0,"preview":""},
    "severity":"ERROR",
    "message":"File not found",
    "code":"ANALYSIS_FAILURE"
  }],
  "fileStatuses": [{"filePath":"/workspace/Missing.kt","state":"MISSING_ON_DISK","message":"File not found"}],
  "semanticOutcome":"INCOMPLETE",
  "requestedFileCount":1,
  "analyzedFileCount":0,
  "skippedFileCount":1,
  "schemaVersion":3
}
```

Invoke `kast --output json agent diagnostics --file-path <missing> --workspace-root <workspace>`. Assert exit failure, command `ok == false`, refresh step `ok == true`, diagnostics step `ok == false`, error code `SEMANTIC_ANALYSIS_INCOMPLETE`, and promoted counts.

Run equivalent fake-server invocations with `--output toon` and `--output human`; assert both contain labels for analyzed `0` and skipped `1`.

Run the fake server once more with an `ANALYZED` status, `COMPLETE` outcome,
and one ordinary compiler `ERROR` whose code is not `ANALYSIS_FAILURE`.
Assert the command exits zero, reports `ok == true`, and promotes analyzed `1`
and skipped `0`.

- [ ] **Step 2: Run the Rust integration test and verify red**

Run:

```bash
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_diagnostics_smoke
```

Expected: JSON invocation exits zero and reports `ok: true`, reproducing the issue.

- [ ] **Step 3: Add semantic failure detection**

Extend `result_failure` before the existing `ok == false` check:

```rust
if result.get("semanticOutcome").and_then(Value::as_str) == Some("INCOMPLETE") {
    let mut error = agent_error(
        "SEMANTIC_ANALYSIS_INCOMPLETE",
        "The backend did not analyze every requested file.",
    );
    error.details.insert("semanticAnalysis".to_string(), semantic_analysis_summary(result));
    return Some(error);
}
if result
    .get("diagnostics")
    .and_then(Value::as_array)
    .is_some_and(|items| items.iter().any(|item| item.get("code").and_then(Value::as_str) == Some("ANALYSIS_FAILURE")))
{
    return Some(agent_error(
        "SEMANTIC_ANALYSIS_FAILED",
        "The backend returned an ANALYSIS_FAILURE diagnostic.",
    ));
}
```

`semantic_analysis_summary` copies only outcome and three counts into a bounded JSON object.

- [ ] **Step 4: Promote the diagnostics summary**

In `execute_agent_steps`, retain the diagnostics result summary before pushing the step result. Add `semanticAnalysis` to the final `KAST_AGENT_COMMAND` result only when those fields are present. The step keeps its full result and error, refresh remains independently successful, and `issues` still contains `AGENT_STEP_FAILED` for the diagnostics step.

- [ ] **Step 5: Run focused Rust checks**

Run:

```bash
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_diagnostics_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_command_surface_smoke --test agent_output_format_smoke
```

Expected: all tests pass; incomplete evidence exits one and all formats expose counts.

- [ ] **Step 6: Commit the CLI slice**

```bash
git add cli-rs/src/agent cli-rs/tests/agent_diagnostics_smoke.rs
git diff --cached --check
git commit -m "fix: fail agent diagnostics on incomplete evidence"
```

---

### Task 5: Generated Contracts and Documentation Evidence

**Files:**
- Modify generated: `cli-rs/protocol/openapi.yaml`
- Modify generated: `cli-rs/protocol/api-reference.md`
- Modify generated: `cli-rs/protocol/api-specification.md`
- Modify generated: `cli-rs/protocol/capabilities.md`
- Modify generated: `cli-rs/protocol/examples/diagnostics-response.json`

**Interfaces:**
- Consumes: Task 1 registered Kotlin serializers and the fake backend example generator.
- Produces: checked-in generated evidence documenting file states, outcome, and counts.

- [ ] **Step 1: Regenerate protocol artifacts from source owners**

Run:

```bash
./gradlew :analysis-api:generateOpenApiSpec :analysis-api:generateDocPages :analysis-server:generateDocExamples
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract
```

- [ ] **Step 2: Verify generated diagnostics evidence**

Run:

```bash
rg -n "fileStatuses|semanticOutcome|requestedFileCount|analyzedFileCount|skippedFileCount" cli-rs/protocol
cargo run --manifest-path cli-rs/Cargo.toml --bin kast -- developer release generate contract --check
.github/scripts/test-docs-content-contract.sh
```

Expected: the diagnostics response schema and example contain all completeness fields; generation check and docs contract pass.

- [ ] **Step 3: Commit generated evidence**

```bash
git add cli-rs/protocol
git diff --cached --check
git commit -m "docs: publish semantic completeness contract"
```

---

### Task 6: Full Verification and Issue Handoff

**Files:**
- Review all branch changes from `origin/main...HEAD`
- No new production files unless verification exposes a defect

**Interfaces:**
- Consumes: all prior tasks.
- Produces: reproducible verification evidence, reviewed commits, pushed branch, PR, and closed issue after merge.

- [ ] **Step 1: Run Kotlin and generated-contract verification**

```bash
./gradlew test
.github/scripts/test-docs-content-contract.sh
```

Expected: `BUILD SUCCESSFUL` and the docs contract exits zero.

- [ ] **Step 2: Run Rust verification**

```bash
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
cargo test --manifest-path cli-rs/Cargo.toml --locked
```

Expected: formatting is clean, Clippy reports no warnings, and every Rust test passes.

- [ ] **Step 3: Review scope and invariants**

```bash
git status --short --branch
git diff --stat origin/main...HEAD
git diff --check origin/main...HEAD
git log --oneline origin/main..HEAD
```

Confirm every #332 acceptance criterion has direct source/test evidence, every requested path gets one status, no unrelated file is staged, and ordinary compiler errors remain analyzed.

- [ ] **Step 4: Perform code review and repair findings**

Use the repository review workflow on `origin/main...HEAD`. For every finding, add a reproducing test or compiler proof, implement the minimal repair, rerun the affected focused checks, and commit with a conventional `fix:` message.

- [ ] **Step 5: Publish #332**

Push `feature/issue-332-fail-closed-analysis`, open a draft PR that includes `Closes #332`, list every verification command, mark ready once checks are green, and babysit GitHub Actions through terminal success. Merge only after required checks pass, verify `origin/main` contains the merge commit, and confirm issue #332 is closed.

- [ ] **Step 6: Continue the parent queue**

Refresh #331 relationships and open issues, select the next unblocked P0 child, and start its isolated design/implementation cycle without redefining completion of the parent goal.
