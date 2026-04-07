---
name: kotlin-standards
description: >
  Produce production-grade Kotlin that is type-driven, boundary-aware, and easy
  to verify. Use when writing, reviewing, or refactoring Kotlin code that
  benefits from strong domain modeling, parse-don't-validate boundaries, value
  classes, sealed types, explicit error semantics, coroutine correctness, or
  rigorous testing. Trigger for Kotlin implementation work, domain modeling,
  API design, refactors away from primitive obsession, null-heavy logic,
  boolean flags, or validation-heavy code.
---

# Kotlin Mastery

Write Kotlin that makes illegal states hard or impossible to represent. Parse
untrusted input at boundaries, move invariants into types, and prove behavior
with executable verification instead of explanation alone.

## Core stance

- Prefer types over comments, conventions, and repeated runtime checks.
- Parse once at trust boundaries, then operate on trusted domain models.
- Keep side effects and infrastructure at the edges; keep core rules pure.
- Preserve existing public behavior unless the task explicitly calls for a
  breaking change.
- Reuse repository-native patterns when they are clearly established in the
  immediate context; do not introduce a second version of the same idea.
- Match the repository's established style before introducing new abstractions.

## Non-negotiable standards

### Domain modeling

- Replace primitive obsession with value classes, enums, or sealed hierarchies
  when domain meaning matters.
- Use sealed types for closed state machines and mutually exclusive outcomes.
- Prefer immutable models with `val`; justify `var` and mutable collections.
- Eliminate illegal states instead of documenting them.

### Boundary discipline

- Treat network payloads, files, environment variables, database rows, CLI
  arguments, and user input as untrusted.
- Parse external input into trusted types at the boundary.
- Avoid `require`, `check`, and exception-first construction for ordinary input
  rejection; prefer parsing or factories that return typed outcomes.
- Do not re-parse the same invariant deeper in the system unless crossing a new
  trust boundary.

### Error semantics

- When replacing throws or designing non-throwing flows, inspect the immediate
  repository context first for existing error ADTs, typed outcomes, or
  repository-standard wrappers and use those as the reference pattern.
- If no local typed-error pattern exists, prefer Kotlin standard-library
  primitives such as `Result` over inventing a new generic wrapper or bringing
  in an ad hoc abstraction.
- Reserve exceptions for truly exceptional conditions or
  repository-established contracts.
- Keep error types precise enough for callers and tests to assert on behavior.

### API and implementation quality

- Keep public APIs small, coherent, and hard to misuse.
- Prefer explicit names over boolean traps and nullable control flags.
- Hide internals aggressively with `private` or `internal`.
- Use KDoc for public APIs and non-obvious invariants.

### Concurrency and state

- Prefer immutable state and confinement over shared mutation.
- When coroutines are present, preserve structured concurrency and make
  cancellation behavior explicit.
- Avoid `GlobalScope`, ambient context assumptions, and hidden shared state.

### Verification

- Add or update tests for any meaningful behavior change.
- Run the repository's real verification commands before claiming success.
- Do not infer build or test success from static inspection alone.

## Reference map

Read extra references only when they help the current task:

- `references/parse-dont-validate-examples.md` for boundary refactors and
  before/after transformations
- `references/types-domain-modeling.md` for value classes, sealed state
  modeling, nullability elimination, and immutability
- `references/types-dsls-and-generics.md` for DSL scoping, variance, reified
  generics, and context receivers
- `references/types-errors-and-testing.md` for typed outcomes, type-safety
  anti-patterns, and verification techniques
- `references/type-safety-patterns.md` as a router when the type-system
  subtopic is not clear yet
- `references/kotlin-antipatterns.md` for smell detection
- `references/idioms.md` for Kotlin-native expression and DSL style
- `references/api-parameter-selection.md` for plain parameters, named
  arguments, overloads, lambda flavours, and constructor-versus-builder choices
- `references/api-builders-and-configuration.md` for builders, receiver
  lambdas, configuration objects, and contract details
- `references/api-extensions-and-factories.md` for extension APIs, framework
  integration, factory naming, and reified shortcuts
- `references/api-surface-stability.md` for visibility, opt-in tiers, and
  mutable-versus-read-only exposure
- `references/api-review-guides.md` for decision flowcharts and API
  anti-pattern review
- `references/api-dsl-choices.md` as a router when the API-design subtopic is
  not clear yet

Prefer the narrowest leaf reference over a router file. Load multiple
references only when the task truly crosses multiple subtopics.

## Workflow

1. **Frame the task**
   - Identify the boundary inputs, trusted outputs, domain invariants, and
     behavior that must remain stable.
   - Note ambiguity explicitly instead of inventing business rules.
2. **Assess the current design**
   - Scan for primitive obsession, nullable state encoding, boolean flags,
     `Map<String, Any>`, unchecked casts, `!!`, repeated validation,
     exception-driven control flow, and hidden shared mutation.
   - Inspect the immediate package, module, and nearby tests for established
     local patterns before introducing a well-known abstraction yourself:
     error ADTs, result wrappers, identifiers, time abstractions, builders,
     DSL markers, and parser entry points.
   - Record the highest-risk issues before editing.
3. **Design the types**
   - Introduce the smallest set of types that capture the real domain: value
     classes, sealed hierarchies, explicit configuration types, and focused
     error models.
   - Prefer the repository's existing best-practice implementation of a common
     pattern when one is present; otherwise fall back to Kotlin standard
     library or already-established general-purpose solutions.
   - Keep constructors private when parsing or invariants must be enforced.
4. **Implement in thin vertical slices**
   - Start with core domain types and pure logic.
   - Add boundary parsers or translators.
   - Integrate with IO, frameworks, or concurrency only after the types are
     stable.
   - Keep diffs small and easy to verify.
5. **Verify behavior**
   - Run the narrowest useful tests while iterating.
   - Run the repository's standard build or test commands before finishing.
   - Treat verification failures as feedback, not as a side note.
6. **Iterate until the quality bar is met**
   - Re-score the change using the scorecard below.
   - Fix the highest-severity failing dimension first.
   - Repeat until all mandatory dimensions pass or an external blocker
     remains.

## Quality scorecard

Score each dimension as `Pass`, `Concern`, or `Fail`.

### 1. Domain fidelity

- `Pass`: Types encode the important business concepts and illegal states are
  difficult or impossible to construct.
- `Concern`: Some important concepts remain primitive or only partially
  encoded.
- `Fail`: Core invariants still rely on comments, scattered checks, or caller
  discipline.

### 2. Boundary parsing

- `Pass`: Untrusted input is parsed once into trusted types with clear failure
  modes.
- `Concern`: Parsing exists but some unchecked or repeated validation remains.
- `Fail`: Raw external data flows through core logic or constructors throw on
  ordinary input mistakes.

### 3. Error design

- `Pass`: Expected failures are explicit, typed, and testable.
- `Concern`: Error paths exist but are coarse, leaky, or hard to assert on.
- `Fail`: Behavior depends on generic exceptions, `null`, or ambiguous
  booleans.

### 4. API ergonomics

- `Pass`: Call sites are clear, misuse is difficult, and public surface area is
  minimal.
- `Concern`: API is workable but exposes avoidable flags, nulls, or internals.
- `Fail`: API invites invalid combinations, temporal coupling, or stringly
  typed usage.

### 5. State and concurrency safety

- `Pass`: State is immutable or intentionally synchronized, and coroutine
  behavior is explicit.
- `Concern`: Some mutable or concurrent behavior is safe but underexplained.
- `Fail`: Hidden shared mutation, lifecycle leaks, or unstructured concurrency
  can cause correctness bugs.

### 6. Test coverage and executability

- `Pass`: Tests cover core success paths, boundary failures, and relevant edge
  cases; verification commands were run.
- `Concern`: Some important edges or failure cases lack tests.
- `Fail`: Changes rely on reasoning alone or unverifiable claims.

### 7. Kotlin idiomatic quality

- `Pass`: Code uses Kotlin-native constructs clearly and avoids Java-shaped
  ceremony.
- `Concern`: Code works but misses obvious Kotlin improvements.
- `Fail`: Code fights the language with unnecessary mutability, reflection, or
  unsafe casts.

## Improvement loop

Use this loop whenever the first draft is not clearly good enough:

1. Write a short findings list ordered by risk.
2. Choose the single highest-value improvement that increases correctness or
   reduces invalid states.
3. Make the smallest change that resolves that issue without widening scope.
4. Re-run the relevant verification commands.
5. Re-score the affected scorecard dimensions.
6. Repeat until the remaining concerns are minor, intentional, or blocked by
   constraints.

When reviewing existing code, report findings in this format:

- `Issue`: what is wrong
- `Why it matters`: the correctness, safety, or maintenance risk
- `Proposed type/design change`: the smallest credible fix
- `Verification`: which test or command proves the improvement

## Testing expectations

Always include:

- Unit tests for pure domain logic
- Boundary tests for parse failures and error typing
- Regression tests for any bug fix or behavior-sensitive refactor

Include when relevant:

- Property-based tests for reducers, parsers, ordering, or combinator-heavy
  logic
- Concurrency tests for shared state or cancellation-sensitive code
- Serialization or fixture tests for external formats

## Completion gate

Do not declare the task complete until all of the following are true:

- The code satisfies the mandatory scorecard dimensions without any `Fail`
  ratings.
- Behavior claims are backed by executed tests or build commands.
- Public or reusable APIs document non-obvious invariants and error semantics.
- Any remaining `Concern` ratings are explicitly called out with rationale or
  follow-up suggestions.
