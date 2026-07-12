# Proof-Loss P0-P3 Integration Design

**Status:** Approved

**Date:** 2026-07-11

## Purpose

Integrate the proof-loss MVP into Kast as an internal, compiler-backed vertical
slice through P0-P3. The integration detects when code establishes a modeled
fact about a raw value, then crosses a modeled boundary with that raw value
instead of a value that carries the proof.

The result is a witness, not a generic validation warning:

```text
proof-establishment site -> resolved value path -> obligated boundary argument
```

The integration preserves the spike's verified reasoning while strengthening
its Kotlin contracts. Facts learned by model construction, K2 resolution,
extraction, and analysis remain represented in types rather than being reduced
to caller convention, nullable sentinels, primitive tags, or free-form text.

## Scope

This design covers:

- P0: exact semantic callable identity;
- P1: control-flow and dominance proof;
- P2: proof-carrier distinction;
- P3: explicit unsupported coverage;
- the pure analyzer and its behavioral test oracle;
- an IDEA/K2 extractor and Kotlin source fixtures;
- backend-internal application invocation for tests; and
- removal of the disposable `kast-proof-loss-mvp/` source after migration.

This design does not cover:

- a public `kast agent` command;
- an `AnalysisBackend` capability or API result;
- JSON-RPC or generated protocol changes;
- CLI, source-index, or release-package changes;
- headless-backend extraction;
- production repository scanning or P4 finding adjudication;
- mutation-aware alias invalidation, loops, nested lambdas, field-sensitive
  values, interprocedural summaries, or inferred semantic models.

Because no public product surface changes, ADR 0006 does not require a
superseding ADR.

## Ownership

The integration remains inside two existing module boundaries.

`backend-shared` owns the host-independent proof vocabulary, model validation,
reduced IR, extraction outcome contract, forward must-analysis, findings, and
application shell. These components have no PSI, K2, IDEA, filesystem IO, or
serialization responsibility.

`backend-idea` owns resolved callable and value identity construction,
declaration-backed model resolution, Kotlin control-flow lowering, IDEA read
access, and source-backed fixtures. K2 lifetime-bound values never cross into
the shared IR.

`analysis-api`, `analysis-server`, `backend-headless`, `index-store`, and
`cli-rs` remain unchanged. A backend-internal fixture harness invokes the
application, but no runtime capability is registered.

The proof-loss code uses small semantic package owners for model, IR and
extraction, analysis, and IDEA extraction concerns. It does not create a broad
flat package whose peer files are distinguished only by repeated prefixes.

## Typed proof vocabulary

### Semantic identities

`ProofCallableKey` is structured identity derived from a resolved callable. It
contains the callable ID, callable kind, declared receiver, declared context
and value-parameter type keys, and generic arity needed to distinguish the
supported callable set. Calls and model declarations use the same conversion.
The engine never compares callee spelling or a single opaque renderer string.

`TrackedValueId` and `ProofFunctionId` contain a normalized source path plus a
typed declaration offset. Local values with the same spelling in different
files or scopes therefore remain distinct.

`ProofSourceSpan` contains a `NormalizedPath` and dedicated non-negative
`SourceOffset` values. PSI text offsets are not represented as byte offsets.

Predicate IDs, boundary IDs, Kotlin type keys, and argument indexes are
constrained domain values with controlled construction. Raw primitives are
accepted only at their construction boundary.

### Model construction

`ProofModel` has no unchecked public constructor. Its factory returns a sealed
success or failure result. Failure contains typed configuration violations for
conditions such as:

- duplicate predicate or boundary IDs;
- duplicate callable identities within a role;
- one callable assigned conflicting semantic roles;
- a boundary obligation naming an unknown predicate;
- duplicate obligations for the same boundary argument and predicate; or
- one materializer claiming incompatible predicate proofs.

A successful model owns defensive immutable copies and validated lookup maps.
No caller can mutate source collections after validation and thereby change
the model's meaning.

Materializer descriptors distinguish total construction from supported
fallible construction. The extractor may emit materialization success only
when construction is total or when a supported failure path exits before the
binding exists.

### Closed outcomes

Proof establishment is a sealed hierarchy. A predicate guard cannot carry a
produced value. Materialization success must carry the value it produced.

IR statements and value expressions form sealed, immutable families. Boundary
arguments use typed non-negative indexes and direct tracked value identities.

Extraction returns exactly one of:

- `Supported`, containing one complete function IR; or
- `Unsupported`, containing the function identity and a non-empty collection
  of sealed, case-specific reasons.

Unsupported variants carry the data appropriate to their case. There is no
separate enum and detail string that can contradict each other. Partial IR is
never returned as supported.

## IDEA extraction

The IDEA implementation has two internal adapters that share one callable-key
conversion.

The model resolver converts actual predicate, materializer, and boundary
declarations into the shared validated model. Tests and internal callers do
not hand-author rendered signatures.

`IdeaProofIrExtractor` accepts one `KtNamedFunction`, owns the IDEA read action,
and performs one `analyze(function)` session. All semantic resolution happens
inside that session. Only shared, lifetime-independent domain values leave it.

### Tracked values

Parameters and local immutable, non-delegated `val` declarations may become
tracked values. References resolve to their declarations before identity is
created. A relevant mutable local, property, delegated value, or unresolved
reference makes the function unsupported.

### Supported lowering

The extractor lowers only the following relevant shapes:

```text
val y = x
val y = totalMaterializer(x)
val y = fallibleMaterializer(x) ?: return|throw
if (predicate(x)) { ... }
if (!predicate(x)) { ... }
return
throw
boundary(x)
```

Syntactic negation is normalized into predicate truth polarity. The analyzer,
not the extractor, decides whether the resulting fact dominates a boundary.

Modeled calls are recognized only by resolved `ProofCallableKey`. Unmodeled
statements become no-ops only when they cannot affect tracked values or
control flow.

Boundary arguments must be positional direct references to stable tracked
values. Named, defaulted, spread, lambda, property-read, and expression
arguments are unsupported in this cut.

When uncertainty can affect a tracked value, modeled call, or control path,
the extractor fails the entire function closed with a precise typed reason and
source location.

## Forward must-analysis

The shared analyzer starts with function parameters as proof-free origins. An
immutable alias retains its origin and extends its value path. A successful
materialization retains the origin, adds the predicate to the value's carried
proof set, and records a materialization witness.

A predicate condition adds a true or false fact for the resolved origin on
each feasible branch. Contradictory paths terminate as unreachable. At a join,
a value or fact survives only when every continuing branch contains the same
value or fact and witness.

A proof-loss finding is emitted only when all of these conditions hold:

1. the boundary has an explicit obligation for the argument and predicate;
2. the predicate fact is true for the argument's raw origin at the boundary;
3. the fact has a proof-establishment witness;
4. the boundary argument does not carry the predicate proof; and
5. the boundary receives the resolved tracked value directly.

Each finding contains the function identity, predicate and boundary
identities, resolved callables, argument index, raw origin, crossing value,
proof-establishment variant and location, boundary location, immutable value
path, and suggested materializers. Application results sort supported
functions, findings, and unsupported functions deterministically.

## Proof matrix

The existing pure analyzer cases remain the behavioral oracle and become
JUnit Jupiter tests in `backend-shared`.

### P0: semantic identity

Kotlin source fixtures cover same-spelling declarations in different packages,
overloads, import aliases, member versus top-level functions, and extension
receivers. Every modeled call must resolve to its declaration-derived key, and
distinct declarations must not collide.

### P1: control proof

Fixtures cover a positive branch, rejecting negative guards with return and
throw, a non-dominating check, a contradictory nested guard, and two
continuing branches. Findings occur only when the positive predicate fact is
in the must-fact set at the boundary.

### P2: proof carrier

Paired fixtures cross the same boundary with a raw value and a materialized
value. They also cover discarded materialization, immutable aliases,
predicate-specific materializers, total construction, guarded nullable
construction, and fallible construction whose success is not proven. A
materializer suppresses only the predicate it declares, and only when the
materialized value crosses the boundary.

### P3: honest coverage

Fixtures cover mutable tracked values, loops, nested lambdas, expression
arguments, unsupported argument mapping, and unresolved calls. Every skipped
function has at least one typed reason. Every finding contains the complete
witness described above. No unsupported construct is accepted through text
matching or partial lowering.

Tests keep callable identity, IR lowering, pure reasoning, model-construction
failure, and end-to-end application behavior separate so failures identify
the responsible boundary.

## Verification

Verification widens in these rings:

1. Run Kast diagnostics for every touched Kotlin file.
2. Run focused proof-loss tests in `backend-shared` and `backend-idea`.
3. Run `./gradlew :backend-shared:test :backend-idea:test`.
4. Run `./gradlew test`.
5. Run `git diff --check`.
6. Complete the Kotlin correctness scorecard with no `Fail` rating for domain
   fidelity, boundary parsing, layout cohesion, error design, state safety,
   test value, Kotlin idiom, filesystem evidence, or Kast semantics.

The pre-change baseline is established: the MVP manifest verifies, all 19
standalone analyzer tests pass, the demo emits the expected witness, Kast
reports a healthy IDEA backend, and both target module test suites pass.

## MVP removal

`kast-proof-loss-mvp/` remains intact while its tests and rationale are used as
migration evidence. It is removed only after the shared and IDEA test matrices
pass. The final change includes an absence check proving that no duplicate MVP
implementation remains.
