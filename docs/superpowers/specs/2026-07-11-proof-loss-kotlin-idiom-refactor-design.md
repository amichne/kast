# Proof-Loss Kotlin Idiom Refactor Design

**Status:** Approved

**Date:** 2026-07-11

## Purpose

Reorganize the internal P0-P3 proof-loss implementation around Kotlin semantic
owners and expression-oriented data flow. The refactor preserves every
existing proof-loss behavior while making sealed worlds, typed failures, and
pure transformations visible in the code shape.

The design follows Kotlin Engineering's package, type, and functional-idiom
standards. It does not pursue point-free style: local names remain when they
identify proof concepts or make a transition auditable.

## Scope

The refactor covers the proof-loss production and test sources in
`backend-shared` and `backend-idea`. It may split and rename files, nest direct
sealed variants, rename types where package context makes a prefix redundant,
and replace incidental mutation or nullable control flow with typed immutable
results.

It does not change P0-P3 semantics, finding order, witness contents,
unsupported coverage, module ownership, or public Kast surfaces. It adds no
API, JSON-RPC, CLI, headless, index, release, or P4 behavior.

## Nesting rule

Each independent closed concept remains a top-level sealed root. Its direct
variants nest exactly one level beneath it:

```kotlin
Statement.Let
Statement.If
ExtractionResult.Supported
UnsupportedReason.Loop
```

The design rejects deeper ownership chains such as
`ExtractionResult.Unsupported.Reason.Loop`. Supporting types that are not
variants remain peers unless one clear owner makes nesting improve the call
site.

## Shared model organization

`backend-shared/.../proofloss/model/` has four semantic files:

- `ProofText.kt` owns text parsing, text-backed IDs, and argument-index parsing.
- `ProofCallable.kt` owns callable/type keys and callable kind.
- `ProofVocabulary.kt` owns materializers, predicates, obligations, and
  boundaries.
- `ProofModel.kt` owns model violations, model build outcomes, validation, and
  indexed lookup.

This keeps the package below the horizontalization concern threshold while
removing the current 213-line multi-owner model file.

## Shared IR organization

`backend-shared/.../proofloss/ir/` has four semantic files:

- `SourceIdentity.kt` owns offsets, spans, function IDs, and tracked-value IDs.
- `Statement.kt` owns `FunctionIr`, `Block`, `PredicateCondition`, and the
  `Statement` sealed hierarchy.
- `ValueExpression.kt` owns the `ValueExpression` sealed hierarchy.
- `Extraction.kt` owns the extractor contract, extraction result, and
  unsupported-reason hierarchy.

The direct statement variants become `Statement.Let`, `Statement.If`,
`Statement.BoundaryCall`, `Statement.Exit`, and `Statement.NoOp`. `ExitKind`
remains a peer enum so the design does not introduce a two-level
`Statement.Exit.Kind` chain.

## IDEA organization

The IDEA package removes redundant filename and type prefixes already supplied
by `io.github.amichne.kast.idea.proofloss`:

- `CallableKey.kt`
- `ModelResolver.kt`
- `IrExtractor.kt`

`ModelSpec` owns direct `Predicate`, `Materializer`, and `Boundary` children.
The resolver becomes `ModelSpec.resolve()` and returns a typed sealed outcome
instead of `ProofModel?`.

## Type renaming

Renames are allowed when they improve call sites without making the concept
generic or ambiguous. Initial approved candidates are:

- `ProofTextParseResult` to `TextParseResult`;
- `ProofCallableKind` to `CallableKind`;
- `ProofCallableKey` to `CallableKey`;
- `ProofModelViolation` to `ModelViolation`;
- `ProofModelBuildResult` to `ModelBuildResult`;
- `ProofObligation` to `Obligation`;
- `ProofSourceSpan` to `SourceSpan`;
- `ProofFunctionId` to `FunctionId`; and
- `ProofIrExtractor` to `IrExtractor`.

`ProofModel`, `ProofLossAnalyzer`, `ProofLossFinding`, `PredicateId`,
`BoundaryId`, and `TrackedValueId` retain their names because shorter names
would reduce clarity outside their declaration file.

Kast resolves each declaration identity before a rename. Kast reference
queries are the first scope probe; compiler failures and focused tests remain
the exhaustive migration proof when the source index has not yet hydrated new
references.

## Pure analysis flow

The analyzer replaces its mutable findings side channel and function-scope
state variable with an immutable private value:

```kotlin
AnalysisStep(
    state: State?,
    findings: List<ProofLossFinding>,
)
```

Block analysis folds statements into `AnalysisStep`. Branch analysis evaluates
both branches and combines their immutable results. Boundary analysis returns
findings through `mapNotNull`. No output collection is passed down the call
tree.

Semantic local values such as `source`, `fact`, `argument`, and `predicate`
remain because they expose the proof transition. Incidental plumbing locals,
manual final returns, semicolon-combined statements, and side-effecting
`also` chains do not.

## Application flow

`ProofLossApplication.run` becomes an expression-bodied fold over extraction
results. A private immutable accumulator owns analyzed function IDs, findings,
and unsupported functions. The result constructor performs deterministic
sorting after the fold. Mutable lists do not cross any function boundary.

## Extractor flow

The IDEA extractor replaces nullable lowering plus a shared mutable reasons
list with a private sealed result:

```kotlin
sealed interface Lowering<out T> {
    data class Emitted<T>(val value: T) : Lowering<T>
    data object Ignored : Lowering<Nothing>
    data class Rejected(
        val reasons: NonEmptyList<UnsupportedReason>,
    ) : Lowering<Nothing>
}
```

Small `map`, `flatMap`, and combination operations express the lowering
pipeline. Block lowering accumulates typed rejections without a mutable output
parameter. A typed normalized condition replaces the current mutable
condition/polarity pair.

Expected unsupported Kotlin remains `Lowering.Rejected` and ultimately
`ExtractionResult.Unsupported`. `Ignored` means only that an unrelated
statement is intentionally outside the tracked proof vocabulary; it never
represents failure.

The refactor removes `!!`, non-local returns from collection lambdas,
`null.also`, and nullable values that conflate ignored input with failed
lowering.

## Model resolution flow

`ModelSpec.resolve()` traverses declaration specs through typed resolution
results. It uses immutable `map` and `toSet` transformations and preserves the
reason a declaration, argument index, callable key, or model build failed.
Model build violations remain the shared `ModelBuildResult.Invalid` outcome.

## Mutation boundary

Mutation remains acceptable only when it is locally confined and clarifies an
aggregation boundary, such as `buildList` or `buildMap` during validation.
The refactor does not replace readable domain transitions with dense `let`
chains merely to remove every local declaration.

## Verification

The refactor proceeds in compiler-checked slices:

1. Split model files without changing behavior and run shared tests.
2. Split IR files and nest statement variants; use compiler errors to migrate
   all call sites; run shared tests.
3. Apply approved type renames after Kast identity resolution; run focused
   tests after each coherent rename group.
4. Refactor analyzer and application to immutable folds; run their focused
   tests.
5. Rename the IDEA files and reshape `ModelSpec`; run IDEA tests.
6. Introduce typed model resolution and lowering results; preserve every
   source-fixture outcome.
7. Run Kast diagnostics on all touched Kotlin files,
   `:backend-shared:test`, `:backend-idea:test`, full `./gradlew test`, and
   `git diff --check`.
8. Complete the Kotlin Engineering scorecard with no `Fail` dimension.

No change is complete from formatting or compilation alone. The existing
P0-P3 fixtures remain the behavioral oracle throughout.
