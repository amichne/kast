---
name: kotlin-standards
description: >
  Use when writing, reviewing, or refactoring Kotlin code that needs type-driven
  design, parse-don't-validate boundaries, scoped package/file layout,
  Kotlin-native expression style, immutable state, explicit errors, coroutine
  safety, or correctness-focused tests.
---

# Kotlin Standards

Write Kotlin whose shape communicates the domain. Make illegal states hard to
construct, keep core logic pure, and prove behavior with focused tests.

## Operating Rules

- Prefer types over comments, conventions, nullable flags, and repeated checks.
- Parse untrusted input at boundaries, then pass trusted domain models inward.
- Keep side effects at the edge; keep the important rules pure and state-free.
- Preserve local public behavior unless the task explicitly asks for a break.
- Follow the nearest established repository pattern before introducing a new
  abstraction.
- Test correctness through observable behavior, not coverage targets.

## Layout Rules

- A package should contain one logical or semantic unit. Avoid layer buckets
  such as `utils`, `common`, `helpers`, or broad cross-cutting packages.
- Use package scope to remove redundant names. Inside `project.workspace`, prefer
  `Parser` over `WorkspaceParser` when the shorter name is still clear.
- Default to one primary public interface, class, value class, or sealed root per
  file. For a sealed hierarchy, keep the root and its variants together unless a
  variant becomes an independently owned subsystem.
- Keep an interface and its small library-owned implementations in the same file
  when they form one unit. Split only when implementations have separate
  ownership, lifecycle, dependencies, or test surface.
- Companion factories and tightly-owned extensions may live in the owning type's
  file. Create an extension file only for integration APIs, many unrelated
  receivers, or a separate package-level vocabulary.
- Do not split cohesive code just to satisfy a mechanical file-size instinct.
  Split when the reader can name the new semantic unit.

For detailed layout heuristics, read
`references/layout-package-code-style.md`.

## Type And Boundary Rules

- Use value classes, enums, sealed hierarchies, and focused data classes when a
  primitive carries domain meaning.
- Make constructors private when invariants require parsing or normalization.
- Prefer typed outcomes for expected failures. Use the repository's existing
  result/error pattern when one is present; otherwise prefer Kotlin standard
  `Result` before inventing a wrapper.
- Reserve exceptions for exceptional conditions or established API contracts.
- Keep public APIs small, coherent, and hard to misuse.

## Style Rules

- Prefer expression-oriented code: `map`, `flatMap`, `fold`, `associate`,
  `partition`, `takeIf`, and `runCatching` when they state the transformation
  directly.
- Avoid transient `var`s, mutable accumulators, and temporary values inside
  functions unless they materially improve clarity or performance.
- Prefer `val`, immutable collections at boundaries, and confined mutation in
  builders or adapters.
- Use explicit names instead of boolean traps, nullable control flags, and type
  prefixes that the package already supplies.
- Hide implementation details with `private` or `internal`.
- Add KDoc for public APIs and non-obvious invariants; do not narrate obvious
  assignments.

## Workflow

1. Frame the behavior: boundary inputs, trusted outputs, invariants, and stable
   public behavior.
2. Inspect the immediate package, tests, and existing abstractions for local
   naming, error, layout, and verification patterns.
3. Choose the narrowest semantic unit that owns the change.
4. Add one tracer-bullet test for the next observable behavior, then implement
   the smallest vertical slice that passes.
5. Refactor only while green: improve names, package boundaries, file ownership,
   type modeling, and expression style.
6. Run the narrowest useful verification command before broadening scope.

## Scorecard

Mark each dimension `Pass`, `Concern`, or `Fail` before finishing:

- Domain fidelity: important concepts are represented by types, not comments or
  caller discipline.
- Boundary parsing: untrusted data is parsed once with clear failures.
- Layout cohesion: packages and files map to semantic units and avoid redundant
  prefixes.
- Error design: expected failures are explicit and testable.
- State safety: core code is immutable or intentionally confined.
- Test value: tests verify correctness themes and boundary failures through
  public behavior.
- Kotlin idiom: code reads as Kotlin, not Java with Kotlin syntax.

## Reference Map

Load only the smallest reference that matches the task:

- `references/layout-package-code-style.md`: package scope, file ownership,
  extensions, expression style, and test themes
- `references/parse-dont-validate-examples.md`: boundary parsing examples
- `references/types-domain-modeling.md`: value classes, sealed state, and
  immutability
- `references/types-errors-and-testing.md`: typed outcomes and test strategy
- `references/types-dsls-and-generics.md`: DSLs, variance, reified generics, and
  receiver scopes
- `references/api-dsl-choices.md`: router for API design questions
- `references/api-parameter-selection.md`: parameters, overloads, and builders
- `references/api-builders-and-configuration.md`: configuration objects and DSL
  builders
- `references/api-extensions-and-factories.md`: extension APIs and factories
- `references/api-surface-stability.md`: visibility, compatibility, and opt-in
  tiers
- `references/api-review-guides.md`: API review prompts
- `references/kotlin-antipatterns.md`: smell checklist
- `references/idioms.md`: concise Kotlin idiom reminders
