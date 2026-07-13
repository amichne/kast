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

## File isolation rule

Every non-private top-level production type owns a same-named Kotlin file.
This includes sealed roots, interfaces, fun interfaces, enums, data classes,
and value classes. Direct sealed variants remain nested with their root, while
companion objects and tightly coupled private implementation helpers remain
with their owner. Top-level functions and extensions stay with their semantic
owner rather than forcing artificial function-only files.

This refactor converges the production types it already touches in
`shared/hierarchy` and `shared/proofloss`; it does not trigger an unrelated
repository-wide migration. ADR 0014 owns the future repository default.

## Shared model organization

`backend-shared/.../proofloss/model/` gives each top-level domain type a
same-named file. `TextParseResult.kt` also owns the internal text parsing
helper; `ProofModel.kt` owns only the model and its private validation
implementation. `ModelViolation` and `ModelBuildResult` remain sealed roots
with direct nested variants in `ModelViolation.kt` and
`ModelBuildResult.kt`.

This removes topic-named aggregation files such as `ProofText.kt`,
`ProofCallable.kt`, and `ProofVocabulary.kt` while preserving package cohesion.

## Shared IR organization

`backend-shared/.../proofloss/ir/` gives each top-level IR type a same-named
file. `Statement.kt`, `ValueExpression.kt`, `ExtractionResult.kt`, and
`UnsupportedReason.kt` each keep only their direct nested sealed variants.
`SourceOffset`, `SourceSpan`, `FunctionId`, `TrackedValueId`, `FunctionIr`,
`Block`, `PredicateCondition`, `PredicatePolarity`, `ExitKind`, and
`IrExtractor` are isolated peers.

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

1. Split model types into same-named files without changing behavior and run shared tests.
2. Split IR types into same-named files and nest statement variants; use compiler errors to migrate
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
8. Run the Kotlin file-isolation structural contract.
9. Complete the Kotlin Engineering scorecard with no `Fail` dimension.

No change is complete from formatting or compilation alone. The existing
P0-P3 fixtures remain the behavioral oracle throughout.
