# Kast Demo — Systematic Improvement Plan

This document describes seven prioritized improvements to the `kast demo` and `kast demo generate` flows in the `amichne/kast` repository. Each item includes the problem statement, reasoning, precise file locations, the changes required, and verification steps. The items are ordered by impact.

---

## Item 1: Parallelize Independent Backend Queries in the Loading Phase

### Problem

The loading phase in both `DemoCommandSupport.runInteractive` and `CliServiceDemoGenBackend.buildReportInstrumented` executes five backend calls strictly sequentially: resolve → references → rename → call-hierarchy → text-search. Only `resolveSymbol` must come first (its result feeds `rename`'s `newName` parameter). The remaining four calls are independent of each other. The wall-clock time is currently the **sum** of all five calls; it should be the resolve time plus the **max** of the parallel group. [2-cite-0](#2-cite-0) [2-cite-1](#2-cite-1)

### Reasoning

`findReferences`, `rename` (dry-run), `callHierarchy`, and `analyzeTextSearch` all depend only on `symbolPosition` and the resolved symbol's simple name. They do not depend on each other's results. The `runLoadingPhase` lambda is already a suspend context, and the project already depends on `kotlinx-coroutines-core`. Kotter's `liveVarOf` delegates are backed by `AtomicReference`, so concurrent `onStepComplete` calls from different coroutines are safe. The visual order of step completions will become non-deterministic (steps may show checkmarks out of order), which is acceptable — the UI shows per-step checkmarks, not a sequential progress bar.

### Changes

#### 1a. `DemoCommandSupport.runInteractive` (`DemoCommandSupport.kt`, lines 217–263)

After `resolveSymbol` completes and `onStepComplete(0, ...)` fires, wrap calls 2–5 in `coroutineScope { }` with four `async { }` blocks. Each async block should:

- Record its own `tStart = System.currentTimeMillis()`
- Execute its backend call
- Call `onStepComplete(index, System.currentTimeMillis() - tStart)`
- Return its result

Then `.await()` all four deferreds and assign the results to the local variables.

```kotlin
// After resolveSymbol + onStepComplete(0, ...)
coroutineScope {
    val refsDeferred = async {
        val t = System.currentTimeMillis()
        val r = cliService.findReferences(runtimeOptions, ReferencesQuery(...)).payload
        onStepComplete(1, System.currentTimeMillis() - t)
        r
    }
    val renameDeferred = async { /* same pattern, index 2 */ }
    val callHierarchyDeferred = async { /* same pattern, index 3 */ }
    val textSearchDeferred = async { /* same pattern, index 4 */ }

    referencesPayload = refsDeferred.await()
    renamePayload = renameDeferred.await()
    callHierarchyPayload = callHierarchyDeferred.await()
    textSearch = textSearchDeferred.await()
}
```

#### 1b. `CliServiceDemoGenBackend.buildReportInstrumented` (`DemoGenCommandSupport.kt`, lines 438–491)

Apply the identical transformation. This method is already `suspend`.

#### 1c. Imports

Both files need: `import kotlinx.coroutines.async` and `import kotlinx.coroutines.coroutineScope`.

### Tests

Existing tests in `DemoCommandSupportTest.kt` and `DemoGenCommandSupportTest.kt` use fakes that return immediately, so they will continue to pass. Verify that all five `onStepComplete` callbacks still fire in `FakeDemoGenBackend.buildReportInstrumented`.

### Verification

```bash
./gradlew :kast-cli:test
```

---

## Item 2: Cache Filesystem Walks Across `analyzeTextSearch` Invocations

### Problem

`analyzeTextSearch` calls `Files.walk(workspaceRoot)` and reads every `.kt` file line-by-line on every invocation. In the `demo generate` flow, this is called:

1. Once per symbol group during curation (`SymbolCurationEngine.buildCuratedFromGroup` → `analyzeTextSearch`)
2. Once per curated symbol during report building (`buildReportInstrumented` → `analyzeTextSearch`)

For a workspace with N symbol groups and M `.kt` files, curation alone does N full filesystem walks. The same files are read from disk N + K times (N groups during curation + K curated symbols during report building). [2-cite-2](#2-cite-2) [2-cite-3](#2-cite-3)

### Reasoning

The workspace contents don't change during a demo run. A single in-memory index of all `.kt` file contents, built once and shared across all `analyzeTextSearch` calls, eliminates all redundant I/O. The cache is scoped to a single instance (not a global singleton), created per demo run and discarded afterward. This avoids stale-cache bugs and aligns with the Kotlin standards skill's rule against singleton state.

### Changes

#### 2a. New file: `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/WorkspaceTextIndex.kt`

```kotlin
internal class WorkspaceTextIndex(
    private val workspaceRoot: Path,
    private val ignoredDirectories: Set<String> = DemoCommandSupport.IGNORED_DIRECTORIES,
) {
    private val fileContents: Map<Path, List<String>> by lazy { buildIndex() }

    fun analyze(symbol: Symbol): DemoTextSearchSummary {
        // Same classification logic as DemoCommandSupport.analyzeTextSearch,
        // but iterates over fileContents instead of calling Files.walk.
    }

    private fun buildIndex(): Map<Path, List<String>> {
        // Files.walk(workspaceRoot), filter .kt, exclude ignored dirs, readLines
    }
}
```

Extract the classification helpers (`classifyTextMatch`, `appearsInsideStringLiteral`, `appearsAsSubstring`, `isIgnoredSearchPath`) from `DemoCommandSupport` into this new class as private functions. They are pure functions with no dependency on `DemoCommandSupport` state.

#### 2b. Update `DemoCommandSupport.analyzeTextSearch`

Keep the existing `open fun analyzeTextSearch` signature for backward compatibility (tests subclass it via `StubDemoCommandSupport`). Internally, delegate to a `WorkspaceTextIndex` instance. For the single-symbol `kast demo` path, this doesn't save I/O (only one call), but it unifies the code path.

#### 2c. Update `SymbolCurationEngine`

Change the constructor to accept a `WorkspaceTextIndex` (or a `(Path, Symbol) -> DemoTextSearchSummary` function) instead of a `DemoCommandSupport`. Currently it takes `DemoCommandSupport` and only uses `analyzeTextSearch`. This decouples curation from the god class. [2-cite-4](#2-cite-4)

#### 2d. Update `CliServiceDemoGenBackend`

Replace `private val demoSupport: DemoCommandSupport` with a `WorkspaceTextIndex` (or the function type). Use it in `buildReportInstrumented` line 480 instead of `demoSupport.analyzeTextSearch`.

#### 2e. Wire it up

In the `demo generate` entry point (wherever `SymbolCurationEngine` and `CliServiceDemoGenBackend` are constructed), create one `WorkspaceTextIndex(workspaceRoot)` and pass it to both.

### Tests

- `SymbolCurationEngineTest` currently uses `StubDemoCommandSupport` (a subclass that overrides `analyzeTextSearch`). Replace with a stub lambda or fake `WorkspaceTextIndex`.
- `DemoCommandSupportTest.analyzeTextSearch` test still works since the public method remains.
- `DemoGenCommandSupportTest` uses `FakeDemoGenBackend` which doesn't call `analyzeTextSearch` — no changes needed.

### Verification

```bash
./gradlew :kast-cli:test
```

---

## Item 3: Pre-compile Regex in `appearsInsideStringLiteral`

### Problem

`appearsInsideStringLiteral` compiles a new `Regex` object on every call. In a large workspace, this function is called for every line of every `.kt` file that contains the symbol name. Regex compilation is expensive relative to the match itself. [2-cite-5](#2-cite-5)

### Reasoning

The regex pattern depends on `symbolName`, which is constant for the duration of a single `analyzeTextSearch` invocation. Compiling once per invocation (once per symbol) instead of once per matching line eliminates thousands of redundant compilations in large workspaces. This is a minimal, safe change with no behavioral difference.

### Changes

#### If Item 2 is done (preferred)

In `WorkspaceTextIndex.analyze`, compile the regex once at the top:

```kotlin
val stringLiteralRegex = Regex("""["'][^"']*${Regex.escape(symbolName)}[^"']*["']""")
```

Pass it to `classifyTextMatch` and `appearsInsideStringLiteral`.

#### If Item 2 is not done

In `DemoCommandSupport.analyzeTextSearch` (line 103), after computing `symbolName`, compile the regex once:

```kotlin
val stringLiteralRegex = Regex("""["'][^"']*${Regex.escape(symbolName)}[^"']*["']""")
```

Change `classifyTextMatch` to accept the pre-compiled regex as a parameter. Change `appearsInsideStringLiteral` to:

```kotlin
private fun appearsInsideStringLiteral(line: String, regex: Regex): Boolean =
    regex.containsMatchIn(line)
```

Update the call site in `analyzeTextSearch` to pass the regex through.

### Tests

No new tests needed. The existing `DemoCommandSupportTest` text-search test validates classification behavior and will catch any regression.

### Verification

```bash
./gradlew :kast-cli:test
```

---

## Item 4: Extract Semantic Units from `DemoCommandSupport`

### Problem

`DemoCommandSupport` is ~700 lines and owns at least four distinct responsibilities: text search analysis, presentation building, symbol selection/matching, and interactive session orchestration. The Kotlin standards skill says "split when the reader can name the new semantic unit" and "default to one primary public interface, class, value class, or sealed root per file." [2-cite-6](#2-cite-6) [2-cite-7](#2-cite-7)

### Reasoning

Each of the four responsibilities has a clear name, clear inputs/outputs, and independent testability. Extracting them reduces cognitive load when reading any single file, makes test files smaller and more focused, and makes it easier to modify one concern without risk to others.

### Changes

#### 4a. Text search analysis → `WorkspaceTextIndex.kt`

If Item 2 is done, this extraction happens naturally. The following move out of `DemoCommandSupport`:

- `analyzeTextSearch` (lines 99–163)
- `classifyTextMatch`, `appearsInsideStringLiteral`, `appearsAsSubstring`, `isIgnoredSearchPath` (lines 486–526)
- `IGNORED_DIRECTORIES`, `SAMPLE_MATCH_LIMIT` constants
- `DemoTextMatchCategory`, `DemoTextMatch`, `DemoTextSearchSummary` data classes

Keep `DemoCommandSupport.analyzeTextSearch` as a thin delegation method if needed for backward compatibility.

#### 4b. Presentation building → `DemoPresentationBuilder.kt`

Create `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/DemoPresentationBuilder.kt` containing:

- `internal object DemoPresentationBuilder` with a `fun build(report: DemoReport, verbose: Boolean): KotterDemoSessionPresentation` method
- Move `presentationFor`, `referencesOperation`, `renameOperation`, `callersOperation`, `renameBranches` into it
- Move `DemoOperationPlayback`, `DemoPhasePlayback` private data classes (lines 591–640+)
- Move the `tl()` helper (line 552)
- Move companion constants: `CALL_TREE_PREVIEW_LIMIT`, `LIVE_LINE_PREVIEW_LIMIT`, `REFERENCE_PREVIEW_LIMIT`, `RENAME_FILE_PREVIEW_LIMIT`, `RENAME_BRANCH_COLUMN_LIMIT`, `SCENARIO_LINE_DELAY_MILLIS`, `SCENARIO_PHASE_DELAY_MILLIS` [2-cite-8](#2-cite-8)

These are pure transformations from `DemoReport` → `KotterDemoSessionPresentation`. They have no side effects, no I/O, and no dependency on `DemoCommandSupport` state.

#### 4c. Keep symbol selection and orchestration in `DemoCommandSupport`

`selectSymbol`, `pickBestMatch`, `workspaceSymbolQueryFor`, `symbolMatchesFilter`, and `runInteractive` remain. After extraction, `DemoCommandSupport` should be ~150–200 lines.

#### 4d. Update `DemoCommandSupport.presentationFor` to delegate

Keep a thin delegation for callers that use `support.presentationFor(...)`:

```kotlin
internal fun presentationFor(report: DemoReport, verbose: Boolean = true): KotterDemoSessionPresentation =
    DemoPresentationBuilder.build(report, verbose)
```

#### 4e. Move shared data classes if needed

`DemoReport`, `DemoFlowOutcome`, `DemoPlaybackResult`, `DemoSymbolChooser`, `TerminalDemoSymbolChooser`, `KotterDemoSessionRunner`, `LiveKotterDemoSessionRunner` currently live at the bottom of `DemoCommandSupport.kt`. If `DemoCommandSupport.kt` is still >300 lines after extraction, move the data classes and interfaces to a `DemoModels.kt` file.

#### 4f. Update imports

All files that import from `DemoCommandSupport` need import updates for any moved types: `CliService.kt`, `DemoGenCommandSupport.kt`, `SymbolCurationEngine.kt`, `ConversationTemplateEngine.kt`, `CliExecution.kt`.

### Tests

- `DemoCommandSupportTest` tests that call `presentationFor` should be updated to test `DemoPresentationBuilder.build` directly.
- `SymbolCurationEngineTest.StubDemoCommandSupport` overrides `analyzeTextSearch` — update the stub if the text search is extracted.

### Verification

```bash
./gradlew :kast-cli:test
```

---

## Item 5: Replace Mutable `var` + `!!` Accumulation with Typed `DemoLoadResult`

### Problem

The loading phase in `runInteractive` uses seven mutable `var` declarations that are assigned inside a lambda and then force-unwrapped with `!!`. The Kotlin standards antipatterns reference explicitly flags `!!`, public `var`s, and mutable state crossing boundaries. [2-cite-9](#2-cite-9) [2-cite-10](#2-cite-10)

### Reasoning

A typed result class eliminates the nullability, the force-unwraps, and the temporal coupling between assignment and use. The compiler enforces that all fields are populated before the result can be constructed.

### Changes

#### 5a. Define `DemoLoadResult`

Add to `DemoCommandSupport.kt` (or `DemoModels.kt` if Item 4 creates it):

```kotlin
internal data class DemoLoadResult(
    val resolvedSymbol: Symbol,
    val runtimeStatus: RuntimeCandidateStatus,
    val daemonNote: String?,       // legitimately nullable — daemon may not emit a note
    val references: ReferencesResult,
    val rename: RenameResult,
    val callHierarchy: CallHierarchyResult,
    val textSearch: DemoTextSearchSummary,
)
```

Every field except `daemonNote` is always non-null on the success path. Making them non-nullable means the compiler enforces completeness.

#### 5b. Refactor the loading phase lambda

Replace the seven `var` declarations with a single `var loadResult: DemoLoadResult? = null`. Inside the lambda, construct the `DemoLoadResult` at the end:

```kotlin
var loadResult: DemoLoadResult? = null
val loadSuccess = runLoadingPhase(...) { onStepComplete ->
    // ... resolve, references, rename, callHierarchy, textSearch ...
    loadResult = DemoLoadResult(
        resolvedSymbol = resolveResult.payload.symbol,
        runtimeStatus = resolveResult.runtime,
        daemonNote = resolveResult.daemonNote,
        references = referencesResult,
        rename = renameResult,
        callHierarchy = callHierarchyResult,
        textSearch = textSearchResult,
    )
}
if (!loadSuccess || loadResult == null) return@runSession DemoFlowOutcome.Failed("Backend queries failed")
val result = loadResult!!  // Single !!, guarded by the null check on the line above
```

**Preferred alternative:** If `runLoadingPhase` can be changed to return `T?` instead of `Boolean`, the lambda can return the `DemoLoadResult` directly and eliminate even the single `!!`. Check `runLoadingPhase` in `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/demo/KotterDemoSymbolPicker.kt` to assess feasibility. If it's used in many places, the `var loadResult` approach is safer.

#### 5c. Update `DemoReport` and `DemoFlowOutcome.Completed` construction

```kotlin
val report = DemoReport(
    workspaceRoot = options.workspaceRoot,
    selectedSymbol = selectedSymbol,
    textSearch = result.textSearch,
    resolvedSymbol = result.resolvedSymbol,
    references = result.references,
    rename = result.rename,
    callHierarchy = result.callHierarchy,
)

DemoFlowOutcome.Completed(
    DemoPlaybackResult(
        report = report,
        runtime = result.runtimeStatus,
        daemonNote = result.daemonNote,
    ),
)
```

### Tests

Existing tests don't exercise the loading phase directly (they use fakes that bypass it). No new tests needed, but all existing tests must still pass.

### Verification

```bash
./gradlew :kast-cli:test
```

---

## Item 6: Remove Unused `kast-demo` 3-Act Models and Renderers

### Problem

The `kast-demo` module contains two parallel model systems. The original 3-act models (`GrepResult`, `ResolutionResult`, `CallerNode`) and their renderers (`renderGrepAct`, `renderResolutionAct`, `renderRippleAct`) are **not imported anywhere** in the active codebase. The active code path uses `DemoGenScreen`, `DualPaneConversation`, and `renderDemoGenScreen`. Dead code creates confusion about which model is authoritative. [2-cite-11](#2-cite-11) [2-cite-12](#2-cite-12) [2-cite-13](#2-cite-13) [2-cite-14](#2-cite-14)

### Reasoning

A grep for `GrepResult`, `ResolutionResult`, `CallerNode`, `renderGrepAct`, `renderResolutionAct`, and `renderRippleAct` across the entire repo returns zero import matches outside the files that define them. `ModulePalette` is only imported by `ResolutionAct.kt` and `RippleAct.kt` — no active code uses it. `ActHeader.kt` exports `renderActHeader` which IS used by the active `DualPaneRenderer.kt` and must be kept. The `kast-demo-spec.md` at the repo root describes CLI options (`--min-refs`, `--noise-ratio`, `--depth`, `--no-ripple`) that don't exist in the current `kast demo` command.

### Changes

#### 6a. Delete unused files

- `kast-demo/src/main/kotlin/io/github/amichne/kast/demo/Models.kt`
- `kast-demo/src/main/kotlin/io/github/amichne/kast/demo/GrepAct.kt`
- `kast-demo/src/main/kotlin/io/github/amichne/kast/demo/ResolutionAct.kt`
- `kast-demo/src/main/kotlin/io/github/amichne/kast/demo/RippleAct.kt`
- `kast-demo/src/main/kotlin/io/github/amichne/kast/demo/ModulePalette.kt` [2-cite-15](#2-cite-15)

#### 6b. Keep `ActHeader.kt`

`renderActHeader` is used by `DualPaneRenderer.kt`. Do not delete.

#### 6c. Update `kast-demo-spec.md`

Either delete `kast-demo-spec.md` or add a header noting it is historical and the current implementation uses the dual-pane conversation model. Deleting is preferred — the AGENTS.md says "Treat `docs/` plus `zensical.toml` as the documentation source of truth," and this spec file at the repo root is not in `docs/` and is misleading. [2-cite-16](#2-cite-16)

#### 6d. Verify no test files reference the deleted types

Search test directories for imports of `GrepResult`, `ResolutionResult`, `CallerNode`, `renderGrepAct`, `renderResolutionAct`, `renderRippleAct`, `ModulePalette`. If any test references exist, update or delete those tests.

### Tests

Run the full test suite to confirm no compilation errors after deletion.

### Verification

```bash
./gradlew :kast-demo:test :kast-cli:test
```

---

## Item 7: Make `DemoPlaybackResult` a Sealed Hierarchy

### Problem

`DemoPlaybackResult` has three nullable fields (`report: DemoReport? = null`, `runtime: RuntimeCandidateStatus? = null`, `daemonNote: String? = null`). On the `Completed` path, `report` and `runtime` are always non-null. On the render-from-file path (`NoOpDemoGenBackend`), all fields are null — `DemoPlaybackResult()` is constructed with defaults. This conflates two different result shapes into one nullable bag, forcing every consumer to handle nullability that can't actually occur. [2-cite-17](#2-cite-17)

### Reasoning

The Kotlin standards antipatterns reference flags "nullable control flags" and "expected errors represented by `null`." A sealed interface with two variants — one for the full-data case and one for the render-only case — eliminates the nullable ambiguity and makes each call site's expectations explicit at the type level.

### Changes

#### 7a. Replace `DemoPlaybackResult` with a sealed interface

In `DemoCommandSupport.kt` (or `DemoModels.kt` if Item 4 creates it):

```kotlin
internal sealed interface DemoPlaybackResult {
    /** Full analysis was performed — report and runtime are available. */
    data class Full(
        val report: DemoReport,
        val runtime: RuntimeCandidateStatus,
        val daemonNote: String? = null,
    ) : DemoPlaybackResult

    /** Rendered from a pre-existing artifact — no live analysis data. */
    data object RenderOnly : DemoPlaybackResult
}
```

`daemonNote` remains nullable in `Full` because the daemon legitimately may not emit a note.

#### 7b. Update `DemoFlowOutcome.Completed`

No change needed — it already wraps `DemoPlaybackResult`. The type just becomes the sealed interface.

```kotlin
data class Completed(val result: DemoPlaybackResult) : DemoFlowOutcome
```

#### 7c. Update construction sites

**`DemoCommandSupport.runInteractive`** (line ~287): Change to:

```kotlin
DemoFlowOutcome.Completed(
    DemoPlaybackResult.Full(
        report = report,
        runtime = result.runtimeStatus,  // or runtimeStatus if Item 5 isn't done
        daemonNote = result.daemonNote,
    ),
)
```

**`DemoGenCommandSupport.runHeadless`** (line ~190) and **`runTerminal`** (line ~314): Same pattern — use `DemoPlaybackResult.Full(...)`.

**`DemoGenCommandSupport` render-from-file path** (wherever `DemoPlaybackResult()` with all defaults is used): Change to `DemoPlaybackResult.RenderOnly`.

#### 7d. Update consumption sites

In `CliExecution.kt` (and any other file that reads `DemoFlowOutcome.Completed`), update pattern matches: [2-cite-18](#2-cite-18)

```kotlin
when (val result = outcome.result) {
    is DemoPlaybackResult.Full -> {
        // Access result.report, result.runtime, result.daemonNote directly — no ?. or !!
    }
    is DemoPlaybackResult.RenderOnly -> {
        // No analysis data available
    }
}
```

This eliminates all `?.report`, `?.runtime`, and `!!` access patterns on `DemoPlaybackResult`.

#### 7e. Update tests

`DemoGenCommandSupportTest` constructs `DemoPlaybackResult` in assertions. Update to use `DemoPlaybackResult.Full(...)` or `DemoPlaybackResult.RenderOnly` as appropriate. Search for `DemoPlaybackResult(` in test files and update each occurrence.

### Verification

```bash
./gradlew :kast-cli:test
```

---

## Execution Order

The items have some dependencies:

| Item | Depends on | Notes |
|:-----|:-----------|:------|
| 1 (Parallelize queries) | None | Can be done first |
| 2 (Cache filesystem walks) | None | Can be done first |
| 3 (Pre-compile regex) | Ideally after 2 | Falls out naturally if 2 is done; standalone change is trivial |
| 4 (Extract from god class) | Ideally after 2 | Text search extraction is the same work as Item 2 |
| 5 (Typed DemoLoadResult) | Ideally after 1 | The parallelized code from Item 1 benefits from the typed result |
| 6 (Remove dead code) | None | Independent, low risk |
| 7 (Sealed DemoPlaybackResult) | None | Independent, low risk |

**Recommended order:** 6 → 2+3+4 (as one batch) → 1+5 (as one batch) → 7.

Items 6 and 7 are safe, isolated deletions/refactors. Items 2, 3, and 4 overlap heavily (the text search extraction serves all three). Items 1 and 5 are the core performance change and its companion cleanup.
