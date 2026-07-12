# Proof-Loss Kotlin Idiom Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reorganize the P0-P3 proof-loss implementation into cohesive Kotlin semantic owners with one-level sealed variants and expression-oriented immutable control flow.

**Architecture:** Split the shared model and IR by domain owner, rename redundant `Proof*` types after Kast identity resolution, and migrate every call site through compiler feedback. Replace analyzer/application mutable side channels and IDEA nullable-plus-mutable lowering with typed immutable result folds while preserving every existing fixture outcome.

**Tech Stack:** Kotlin, JUnit Jupiter, IntelliJ Platform/K2 Analysis API, Gradle, Kast semantic tooling.

## Global Constraints

- Preserve P0-P3 semantics, deterministic ordering, witnesses, and unsupported coverage.
- Keep independent sealed roots top-level and nest only direct variants one level beneath them.
- Retain semantic locals that name proof transitions; remove incidental mutation, `!!`, `null.also`, non-local collection returns, and manual final returns.
- Do not modify public API, JSON-RPC, CLI, headless, index, release, or P4 surfaces.
- Resolve rename candidates with Kast first; use compiler failures and focused tests as exhaustive migration proof.

---

### Task 1: Split and rename the shared model

**Files:**
- Create: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/proofloss/model/ProofText.kt`
- Create: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/proofloss/model/ProofCallable.kt`
- Create: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/proofloss/model/ProofVocabulary.kt`
- Modify: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/proofloss/model/ProofModel.kt`
- Modify: proof-loss tests and IDEA imports reported by the compiler.

**Interfaces:**
- Produces `TextParseResult`, `CallableKind`, `CallableKey`, `ModelViolation`, `ModelBuildResult`, and `Obligation`.
- Retains `PredicateId`, `BoundaryId`, `ArgumentIndex`, `ProofModel`, descriptors, and materializer semantics.

- [ ] **Step 1: Resolve rename identities with Kast**

Run `kast agent symbol --references` for each current declaration and retain the resolved FQ identity as evidence.

- [ ] **Step 2: Split owners and apply coherent renames**

Move declarations without compatibility aliases. Keep direct variants nested:

```kotlin
sealed interface TextParseResult<out T> {
    data class Valid<T>(val value: T) : TextParseResult<T>
    data object Blank : TextParseResult<Nothing>
}

sealed interface ModelBuildResult {
    data class Valid(val model: ProofModel) : ModelBuildResult
    data class Invalid(val violations: NonEmptyList<ModelViolation>) : ModelBuildResult
}
```

- [ ] **Step 3: Compile and migrate every call site**

Run `./gradlew :backend-shared:compileTestKotlin :backend-idea:compileTestKotlin`; update only compiler-reported proof-loss references.

- [ ] **Step 4: Verify the model slice**

Run `./gradlew :backend-shared:test --tests '*.ProofModelTest'` and Kast diagnostics for all model files.

### Task 2: Split IR owners and nest statement variants

**Files:**
- Create: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/proofloss/ir/SourceIdentity.kt`
- Create: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/proofloss/ir/Statement.kt`
- Create: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/proofloss/ir/ValueExpression.kt`
- Create: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/proofloss/ir/Extraction.kt`
- Delete: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/proofloss/ir/ProofIr.kt`
- Modify: analyzer, application, IDEA extractor, and tests.

**Interfaces:**
- Produces `SourceSpan`, `FunctionId`, `IrExtractor`, `Statement.Let`, `Statement.If`, `Statement.BoundaryCall`, `Statement.Exit`, and `Statement.NoOp`.
- Retains top-level `ExitKind`, `ValueExpression`, `ExtractionResult`, and `UnsupportedReason` sealed roots.

- [ ] **Step 1: Move source identity and extraction declarations**

Apply `ProofSourceSpan` → `SourceSpan`, `ProofFunctionId` → `FunctionId`, and `ProofIrExtractor` → `IrExtractor` with no aliases.

- [ ] **Step 2: Nest direct statement variants**

Use this closed shape:

```kotlin
sealed interface Statement {
    val location: SourceSpan
    data class Let(...) : Statement
    data class If(...) : Statement
    data class BoundaryCall(...) : Statement
    data class Exit(...) : Statement
    data class NoOp(...) : Statement
}
```

- [ ] **Step 3: Compile and migrate all call sites**

Run both proof-loss test compilation tasks and fix every compiler-reported old type or nested variant.

- [ ] **Step 4: Verify the IR slice**

Run all `backend-shared` proof-loss tests and Kast diagnostics for the four IR files.

### Task 3: Make analyzer and application immutable folds

**Files:**
- Modify: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/proofloss/analysis/ProofLossAnalyzer.kt`
- Modify: `backend-shared/src/main/kotlin/io/github/amichne/kast/shared/proofloss/application/ProofLossApplication.kt`
- Test: existing analyzer and application tests.

**Interfaces:**
- Adds private `AnalysisStep(state, findings)` and private application accumulator types.
- Removes mutable output parameters and function-scope state variables.

- [ ] **Step 1: Refactor block analysis under green tests**

Implement `Block.statements.fold(AnalysisStep(...))`; make statement evaluation and branch combination return immutable steps.

- [ ] **Step 2: Refactor boundary analysis**

Return `List<ProofLossFinding>` through `mapNotNull`, retaining semantic `boundary`, `argument`, `fact`, and `predicate` locals.

- [ ] **Step 3: Refactor application aggregation**

Use an expression-bodied `run` and immutable fold accumulator over `ExtractionResult`.

- [ ] **Step 4: Verify focused behavior**

Run `ProofLossAnalyzerTest` and `ProofLossApplicationTest`, then Kast diagnostics for both production files.

### Task 4: Reshape IDEA model resolution and lowering

**Files:**
- Create: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/proofloss/CallableKey.kt`
- Create: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/proofloss/ModelResolver.kt`
- Create: `backend-idea/src/main/kotlin/io/github/amichne/kast/idea/proofloss/IrExtractor.kt`
- Delete: the three `IdeaProof*.kt` production files.
- Modify: `backend-idea/src/test/kotlin/io/github/amichne/kast/idea/proofloss/IdeaProofIrExtractorTest.kt`

**Interfaces:**
- Produces `ModelSpec` with direct nested `Predicate`, `Materializer`, and `Boundary` children.
- Produces typed `ModelResolution` and private generic `Lowering<T>` sealed results.
- Retains K2-resolved callable identity and `ExtractionResult` output.

- [ ] **Step 1: Add a failing typed model-resolution assertion**

Extend the IDEA fixture test so an unresolved declaration or invalid argument index yields `ModelResolution.Rejected`, not `null`.

- [ ] **Step 2: Verify RED**

Run `./gradlew :backend-idea:test --tests '*.IdeaProofIrExtractorTest'`; expect missing `ModelResolution`/new `ModelSpec` API.

- [ ] **Step 3: Implement typed model traversal**

Make `ModelSpec.resolve()` expression-bodied and use immutable `map`/`toSet` traversal with case-specific rejection variants.

- [ ] **Step 4: Replace mutable lowering reasons**

Implement `Lowering.Emitted`, `Lowering.Ignored`, and `Lowering.Rejected`; compose blocks and statements without mutable reason parameters, `!!`, or `null.also`. Model predicate polarity as a typed normalized condition.

- [ ] **Step 5: Verify GREEN**

Run the IDEA proof-loss fixture and Kast diagnostics on all three IDEA production files and the test.

### Task 5: Review, verify, score, and commit

**Files:** all proof-loss Kotlin changes and this plan.

- [ ] **Step 1: Run focused owning-module verification**

```console
./gradlew :backend-shared:test :backend-idea:test
```

- [ ] **Step 2: Run full repository verification**

```console
./gradlew test
git diff --check
```

- [ ] **Step 3: Run Kast semantic verification**

Run diagnostics on every changed Kotlin file and resolve the renamed sealed roots and cross-module types.

- [ ] **Step 4: Complete Kotlin Engineering review and scorecard**

Audit domain fidelity, boundary parsing, layout cohesion, error design, state safety, test value, Kotlin idiom, filesystem evidence, and Kast semantics. No dimension may be `Fail`.

- [ ] **Step 5: Commit the scoped refactor**

```console
git commit -m "refactor: organize proof-loss Kotlin model"
```
