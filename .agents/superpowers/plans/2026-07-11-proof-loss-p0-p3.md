# Proof-Loss P0-P3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate the proof-loss MVP through P0-P3 as a typed pure engine in `backend-shared` and a fail-closed K2 extractor in `backend-idea`, then remove the disposable MVP directory.

**Architecture:** `backend-shared` owns validated proof vocabulary, immutable IR, must-analysis, and application results. `backend-idea` resolves actual declarations and Kotlin source into that pure model without leaking K2 lifetime owners. No public API, JSON-RPC, CLI, headless, or source-index surface changes.

**Tech Stack:** Kotlin 2.x, JUnit Jupiter, IntelliJ Platform 2025.3, Kotlin Analysis API, Gradle, Kast typed agent commands.

## Global Constraints

- Stop after P0-P3; do not implement P4 repository adjudication.
- Do not modify `AnalysisBackend`, `analysis-api`, `analysis-server`, generated protocol, `backend-headless`, `index-store`, or `cli-rs`.
- Resolve model declarations and calls through the same K2 callable-key conversion; never classify by source spelling.
- Do not allow K2 lifetime-bound objects into `backend-shared` values.
- Return complete supported IR or non-empty typed unsupported coverage; never analyze partial IR.
- Remove `kast-proof-loss-mvp/` only after migrated tests pass.
- Follow red-green-refactor for each task and run Kast diagnostics after Kotlin edits.

---

### Task 1: Validated proof vocabulary

**Files:**
- Create: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/proofloss/model/ProofModel.kt`
- Test: `backend-shared/src/test/kotlin/io/github/amichne/kast/shared/proofloss/model/ProofModelTest.kt`

**Interfaces:**
- Produces: `PredicateId`, `BoundaryId`, `ProofCallableKey`, `KotlinTypeKey`, `SourceOffset`, `ArgumentIndex`, `MaterializerDescriptor`, `PredicateDescriptor`, `BoundaryDescriptor`, `ProofObligation`, `ProofModelBuildResult`, and `ProofModel`.
- `ProofCallableKey` contains callable ID, callable kind, receiver type, context-parameter types, value-parameter types, and generic arity.
- `ProofModel.build(...)` returns `ProofModelBuildResult.Valid` or `ProofModelBuildResult.Invalid(violations: NonEmptyList<ProofModelViolation>)`.

- [ ] **Step 1: Write failing model tests**

Add JUnit tests proving valid lookup, defensive copies, duplicate IDs, unknown predicates, duplicate obligations, materializer conflicts, and callable-role conflicts. Use the wished-for factory API:

```kotlin
val result = ProofModel.build(predicates, boundaries)
val model = assertInstanceOf(ProofModelBuildResult.Valid::class.java, result).model
assertEquals(predicate.id, model.predicateForCallable(predicate.callable)?.id)
```

- [ ] **Step 2: Verify RED**

Run:

```console
./gradlew :backend-shared:test --tests '*.ProofModelTest'
```

Expected: compilation fails because the proof-loss model package does not exist.

- [ ] **Step 3: Implement the narrow model**

Use private or controlled construction for constrained primitives, defensive `toList()`/`toSet()` copies, and a sealed violation family. The successful model alone exposes lookup functions:

```kotlin
sealed interface ProofModelBuildResult {
    data class Valid(val model: ProofModel) : ProofModelBuildResult
    data class Invalid(val violations: NonEmptyList<ProofModelViolation>) : ProofModelBuildResult
}

class ProofModel private constructor(...) {
    companion object {
        fun build(
            predicates: List<PredicateDescriptor>,
            boundaries: List<BoundaryDescriptor>,
        ): ProofModelBuildResult
    }
}
```

- [ ] **Step 4: Verify GREEN and diagnostics**

Run the focused test and:

```console
kast agent diagnostics --workspace-root "$PWD" --file-path "$PWD/backend-shared/src/main/kotlin/io/github/amichne/kast/shared/proofloss/model/ProofModel.kt" --file-path "$PWD/backend-shared/src/test/kotlin/io/github/amichne/kast/shared/proofloss/model/ProofModelTest.kt"
```

Expected: focused tests pass; Kast reports no diagnostics.

### Task 2: Immutable IR and pure must-analysis

**Files:**
- Create: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/proofloss/ir/ProofIr.kt`
- Create: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/proofloss/analysis/ProofLossAnalyzer.kt`
- Test: `backend-shared/src/test/kotlin/io/github/amichne/kast/shared/proofloss/analysis/ProofLossAnalyzerTest.kt`

**Interfaces:**
- Consumes: validated model types from Task 1.
- Produces: `ProofFunctionId`, `TrackedValueId`, `ProofSourceSpan`, sealed `Statement`, sealed `ValueExpression`, sealed `ExtractionResult`, sealed `UnsupportedReason`, sealed `ProofEstablishment`, `ProofLossFinding`, and `ProofLossAnalyzer.analyze`.

- [ ] **Step 1: Write the analyzer tests first**

Port the 19 spike behaviors to JUnit and add explicit tests for sealed witness variants and non-empty unsupported coverage. The public behavior remains:

```kotlin
val finding = ProofLossAnalyzer(model).analyze(function).single()
assertEquals(raw, finding.subject)
assertEquals(alias, finding.boundaryArgument)
assertEquals(listOf(raw, alias), finding.valuePath)
```

- [ ] **Step 2: Verify RED**

Run:

```console
./gradlew :backend-shared:test --tests '*.ProofLossAnalyzerTest'
```

Expected: compilation fails because IR and analyzer types are missing.

- [ ] **Step 3: Implement immutable IR and must-analysis**

Use sealed statement/expression families, source-backed tracked identities, typed offsets, and complete `Supported`/`Unsupported` extraction outcomes. Implement forward must-analysis so a join keeps only identical values and facts from every continuing branch. Represent witnesses as:

```kotlin
sealed interface ProofEstablishment {
    val location: ProofSourceSpan
    data class PredicateGuard(override val location: ProofSourceSpan) : ProofEstablishment
    data class MaterializationSuccess(
        override val location: ProofSourceSpan,
        val producedValue: TrackedValueId,
    ) : ProofEstablishment
}
```

- [ ] **Step 4: Verify GREEN and diagnostics**

Run the focused analyzer test and Kast diagnostics for all three files. Expected: tests pass and diagnostics are empty.

### Task 3: Deterministic application shell

**Files:**
- Create: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/proofloss/application/ProofLossApplication.kt`
- Test: `backend-shared/src/test/kotlin/io/github/amichne/kast/shared/proofloss/application/ProofLossApplicationTest.kt`

**Interfaces:**
- Consumes: `ProofModel`, `ProofIrExtractor<S>`, and `ProofLossAnalyzer`.
- Produces: `ProofLossApplication<S>.run` and `ProofLossRun` with deterministically ordered analyzed IDs, findings, and unsupported functions.

- [ ] **Step 1: Write failing application tests**

Prove mixed supported/unsupported input, deterministic ordering, and preservation of typed unsupported reasons.

- [ ] **Step 2: Verify RED**

Run `./gradlew :backend-shared:test --tests '*.ProofLossApplicationTest'`; expect missing application types.

- [ ] **Step 3: Implement and verify GREEN**

Implement one pass over sources, analyze only `Supported`, retain every `Unsupported`, and sort using typed identity/source ordering. Re-run the test and Kast diagnostics.

### Task 4: P0 declaration-backed callable identity

**Files:**
- Create: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/proofloss/IdeaProofCallableKey.kt`
- Create: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/proofloss/IdeaProofModelResolver.kt`
- Test: `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/proofloss/IdeaProofCallableKeyTest.kt`

**Interfaces:**
- Consumes: `KtNamedFunction`, `KtCallElement`, and typed IDEA model declaration specifications.
- Produces: one `KaFunctionSymbol.toProofCallableKey()` conversion used by both calls and declarations, plus a sealed model-resolution result.

- [ ] **Step 1: Write P0 Kotlin source fixtures**

Create real project fixtures for same spelling in different packages, overloads, import aliases, member/top-level calls, and extension receivers. Resolve declarations and calls; assert intended keys are equal and distinct targets do not collide.

- [ ] **Step 2: Verify RED**

Run `./gradlew :backend-idea:test --tests '*.IdeaProofCallableKeyTest'`; expect missing IDEA proof-loss adapters.

- [ ] **Step 3: Implement one K2 identity conversion**

Within `analyze`, resolve a `KaFunctionSymbol` and construct `ProofCallableKey` from callable ID, kind, declared receiver/context/value-parameter type keys, and generic arity. The model resolver must accept declarations, call the same conversion, and pass only pure keys to `ProofModel.build`.

- [ ] **Step 4: Verify GREEN and P0 diagnostics**

Run the focused test and Kast diagnostics on the adapter and test files. Expected: all P0 fixtures pass with no diagnostics.

### Task 5: P1-P3 fail-closed IDEA extraction

**Files:**
- Create: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/proofloss/IdeaProofIrExtractor.kt`
- Test: `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/proofloss/IdeaProofIrExtractorTest.kt`
- Test: `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/proofloss/IdeaProofLossApplicationTest.kt`

**Interfaces:**
- Consumes: a validated `ProofModel` and `KtNamedFunction`.
- Produces: `IdeaProofIrExtractor.extract(function): ExtractionResult`, owning one IDEA read action and one K2 `analyze(function)` session.

- [ ] **Step 1: Write the P1 positive-branch fixture**

Assert source `if (predicate(raw)) { boundary(raw) }` lowers to a predicate condition and boundary call, then produces one complete witness through the application.

- [ ] **Step 2: Verify RED, implement the minimal P1 slice, verify GREEN**

Run the focused extractor/application tests before and after implementing parameter identity, call classification, positive `if`, direct boundary arguments, and source spans.

- [ ] **Step 3: Add P1 control fixtures before implementation**

Add rejecting negative guards with return and throw, a non-dominating branch, contradictory nested guards, and two continuing branches. Run to observe the expected failures, then implement negation, exits, nested blocks, and branch lowering.

- [ ] **Step 4: Add P2 carrier fixtures before implementation**

Add raw/refined pairs, discarded materialization, immutable aliases, predicate-specific materializers, total materialization, nullable materialization guarded by Elvis return/throw, and unproven fallible construction. Run RED, then implement alias/materialization lowering and proof-carrying values.

- [ ] **Step 5: Add P3 unsupported fixtures before implementation**

Add mutable tracked values, loops, nested lambdas, expression arguments, named/default/spread mapping, property reads, and unresolved calls. Run RED, then implement complete fail-closed typed unsupported results with non-empty reasons and precise spans.

- [ ] **Step 6: Verify P1-P3 GREEN and diagnostics**

Run both focused IDEA tests and Kast diagnostics on extractor and tests. Expected: P1-P3 matrix passes and diagnostics are empty.

### Task 6: Remove the MVP and verify the integration

**Files:**
- Delete: `kast-proof-loss-mvp/`
- Modify only if required by discovered source ownership: nearest scoped `AGENTS.md`
- Update: `.agent-turn/kotlin-agentic-correctness/<session>/scorecard.json` (ignored evidence)

**Interfaces:**
- Produces: one integrated implementation with no duplicate spike and no public surface expansion.

- [ ] **Step 1: Run migrated focused suites before deletion**

Run all proof-loss tests in both modules. Expected: pass.

- [ ] **Step 2: Delete the disposable MVP with a scoped patch**

Remove every tracked path under `kast-proof-loss-mvp/`. Confirm `git status` shows deletions/replacements only within the intended integration scope.

- [ ] **Step 3: Run widening verification**

Run:

```console
./gradlew :backend-shared:test :backend-idea:test
./gradlew test
git diff --check
test ! -e kast-proof-loss-mvp
```

Expected: every command exits zero.

- [ ] **Step 4: Run semantic verification and scorecard**

Run `kast agent diagnostics` on every changed Kotlin file, resolve the new core and extractor symbols with `kast agent symbol`, and write a Kotlin correctness scorecard with no `Fail` dimension.

- [ ] **Step 5: Review and commit**

Review the scoped diff for primitive leakage, nullable state encoding, partial IR, text matching, duplicated model logic, and public-surface drift. Stage only the proof-loss implementation, tests, MVP deletion, plan, and any required scoped instruction update. Commit with:

```console
git commit -m "feat: integrate typed proof-loss analysis"
```
