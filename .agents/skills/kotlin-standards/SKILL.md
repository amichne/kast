---
name: kotlin-standards
description: >
  Use when writing, reviewing, or refactoring Kotlin code that needs type-driven
  design, deep modules with small interfaces, parse-don't-validate boundaries,
  scoped package/file layout, Kotlin-native expression style, immutable state,
  explicit errors, coroutine safety, or correctness-focused tests.
---

# Kotlin Standards

Write Kotlin whose shape communicates the domain. Use the type system as the
primary safety mechanism, make illegal states hard to construct, prefer deep
modules with small interfaces, keep core logic pure, and prove behavior with
focused tests.

## Operating Rules

- Use the type system as the first line of defense. Prefer value classes,
  sealed ADTs, typed queries, and invariant-carrying wrappers over comments,
  caller discipline, nullable control flags, and repeated checks.
- Encode validation outcomes as sealed ADTs so parse/validate/derive steps are
  explicit, composable, and portable across layers.
- Parse untrusted input at boundaries, then pass trusted domain models inward.
- Prefer deep modules: expose the smallest interface that can own the workflow,
  keep orchestration and helper churn inside, and avoid pass-through
  abstractions that just spread the same steps across more files.
- Drive high leverage refactors through the test suite. Add a tracer-bullet test for the next
  observable behavior, then refactor only while green to improve names, layout,
  types, and style.
- Create a seam only when variation is real. A single public adapter is often
  indirection; a production adapter plus a test adapter is a much stronger
  reason.
- Keep side effects at the edge; keep the important rules pure and state-free.
- Prefer immutable models and collections by default. Confine mutation to
  builders, adapters, caches, and measured hot paths.
- Treat background work, caches, filesystem cleanup, and clocks as lifecycle
  dependencies that need explicit ownership in production and deterministic
  control in tests.
- Preserve local public behavior unless the task explicitly asks for a break.
- Follow the nearest established repository pattern before introducing a new
  abstraction.
- Test correctness through observable behavior, not coverage targets.

## Layout Rules

- A package should contain one logical or semantic unit. Avoid layer buckets
  such as `utils`, `common`, `helpers`, or broad cross-cutting packages.
- Favor deeper modules over helper clusters. If callers still coordinate
  parse/validate/normalize/execute steps, the module is too shallow.
- Use package scope to remove redundant names. Inside `project.workspace`, prefer
  `Parser` over `WorkspaceParser` when the shorter name is still clear.
- Prefer one root declaration per file whenever the file contains a non-nested
  type. If more names want to live at the top level, nest them under the owning
  root or move them into their own files.
- When a family of public classes grows into a litany file, create a named
  semantic subpackage and split each primary class, interface, or object into
  its own file. Keep only tightly owned helper functions or extensions clustered
  when they do not deserve independent discovery.
- Use nesting to remove prefixes and ambiguity. Prefer `CliOutput.Text` or
  `ParseResult.Invalid` over flat names like `CliTextOutput` or
  `InvalidParseResult` when the enclosing type already carries the context.
- Keep a sealed root and its variants together by default. Nested variants are
  usually clearer than multiple sibling root declarations with repeated
  prefixes.
- Keep an interface and its small library-owned implementations in the same file
  when they form one unit. Split only when implementations have separate
  ownership, lifecycle, dependencies, or test surface.
- Prefer private or `internal` helpers over new public submodules when the code
  exists only to support one owning workflow.
- Avoid public top-level helper functions when the behavior belongs to a type or
  workflow. Prefer a companion, nested declaration, or owning root when that
  keeps names shorter and more local.
- Companion factories and tightly-owned extensions may live in the owning type's
  file. Create an extension file only for integration APIs, many unrelated
  receivers, or a separate package-level vocabulary.
- Do not extract pure helpers or thin interfaces just for testability. Test
  through the owning module's interface unless the extracted unit earns
  independent leverage.
- Do not split cohesive code just to satisfy a mechanical file-size instinct.
  Split when the reader can name the new semantic unit.

For detailed layout heuristics, read
`references/layout-package-code-style.md`.

## Type And Boundary Rules

- Use value classes, enums, sealed hierarchies, focused data classes, and
  invariant-carrying wrappers when a primitive or open shape carries domain
  meaning.
- Make constructors private when invariants require parsing or normalization.
  Prefer `parse`, `of`, or factory functions when a value must become trusted
  exactly once.
- Prefer parsed and validated models downstream. After a boundary, pass typed
  queries and domain objects, not raw strings, offsets, and partially checked
  nullable fields.
- Prefer typed outcomes for expected failures. Use the repository's existing
  result/error pattern when one is present; otherwise prefer Kotlin standard
  `Result` before inventing a wrapper.
- Prefer sealed ADTs for commands, states, and outcomes whenever callers branch
  on the variant. Exhaustive `when` should be part of the design.
- Use a sealed error type when callers branch on failure reasons. Use
  accumulation when a boundary must report multiple independent input problems.
  Use `Result` for a single expected success/failure value with no typed recovery
  branches.
- Reserve exceptions for exceptional conditions or established API contracts.
- Keep public APIs small, coherent, and hard to misuse.

## Boundary Modeling Rules

- Keep transport models separate from domain models. JSON, CLI, database, and
  wire annotations belong at the boundary unless the type is truly a public wire
  contract.
- Decide the unknown-field policy at the boundary: reject for strict commands,
  ignore for forward-compatible inputs, or preserve for audit/pass-through. Test
  the chosen policy.
- Collapse conflicting optional fields into one domain concept. Prefer sealed
  intent such as `Schedule.Now` or `Schedule.At(time)` over nullable fields plus
  boolean flags.
- Normalize once while parsing, then stop trimming, lowercasing, splitting, or
  re-validating the same primitive inside core logic.

## Concurrency And Lifecycle Rules

- Assume compiler sessions, parsers, mutable caches, and lazy indexes are not
  thread-safe until the owning API documents concurrency guarantees.
- Serialize access around non-thread-safe resources with the narrowest lock that
  preserves correctness. Prefer per-item or per-session critical sections over a
  process-wide lock.
- Make background workers cancellable and joinable. Tests should disable,
  replace, or await background work before cleanup.
- Do not share mutable collections across coroutines without ownership or
  synchronization. Snapshot at boundaries when readers and writers overlap.

## Style Rules

- Prefer expression-oriented code: `map`, `flatMap`, `fold`, `associate`,
  `partition`, `zip`, `buildList`, exhaustive `when`, `takeIf`, and
  `runCatching` when they state the transformation directly.
- Use higher-order functions when they make transformation or policy explicit:
  classification, mapping, folding, ordering, filtering, or effect wrapping.
  Do not hide core control flow behind callback soup or scope-function cleverness.
- Avoid transient `var`s, mutable accumulators, and temporary values inside
  functions unless they materially improve clarity or performance.
- Prefer `val`, immutable collections at boundaries, and confined mutation in
  builders or adapters.
- Use explicit names instead of boolean traps, nullable control flags, and type
  prefixes that the package already supplies.
- Treat repeated prefixes as a smell. If a name needs a long prefix to avoid
  collisions, prefer nesting or a different file boundary over flatter scope.
- Hide implementation details with `private` or `internal`.
- Add KDoc for public APIs and non-obvious invariants; do not narrate obvious
  assignments.

## Workflow

1. Frame the behavior: boundary inputs, trusted outputs, invariants, and stable
   public behavior.
2. Inspect the immediate package, tests, and existing abstractions for local
   naming, error, layout, and verification patterns.
3. Choose the narrowest semantic unit that can hide complexity behind a small
   interface. Pull broad orchestration inward before creating new seams.
4. Add one tracer-bullet test for the next observable behavior, then implement
   the smallest vertical slice that passes.
5. Refactor only while green: improve names, package boundaries, file ownership,
   type modeling, module depth, and expression style.
6. Run the narrowest useful verification command before broadening scope.

## Scorecard

Mark each dimension `Pass`, `Concern`, or `Fail` before finishing:

- Domain fidelity: important concepts are represented by types, not comments or
  caller discipline.
- Boundary parsing: untrusted data is parsed once with clear failures.
- Module depth: callers learn a small interface while workflow coordination and
  helper detail stay inside the module.
- Layout cohesion: packages and files map to semantic units, keep one root
  declaration per file, and avoid redundant prefixes through scope.
- Error design: expected failures are explicit and testable.
- State safety: core code is immutable or intentionally confined.
- Lifecycle safety: background work, locks, caches, and cleanup have explicit
  ownership and deterministic tests.
- Test value: tests verify correctness themes and boundary failures through
  public behavior.
- Kotlin idiom: code reads as Kotlin, not Java with Kotlin syntax.

## Reference Map

Load only the smallest reference that matches the task:

- `references/layout-package-code-style.md`: package scope, file ownership,
  extensions, expression style, and test themes
- `references/module-depth.md`: deep modules, leverage/locality, real seams,
  and interface-shaped tests
- `references/parse-dont-validate-examples.md`: boundary parsing examples
- `references/types-domain-modeling.md`: value classes, parsed models,
  invariant wrappers, sealed state, and immutability
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
