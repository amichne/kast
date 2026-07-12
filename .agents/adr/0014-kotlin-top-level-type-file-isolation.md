# ADR 0014: Kotlin top-level type file isolation

Status: Accepted

Date: 2026-07-12

## Decision

Production Kotlin source uses one non-private top-level named type per file,
and the filename matches that type. The rule covers classes, data classes,
value classes, enum classes, annotation classes, sealed roots, interfaces,
fun interfaces, and named object declarations.

Nested declarations remain with the type that owns their lifecycle or closed
world. In particular, direct variants of a sealed root stay nested beneath
that root. Companion objects and anonymous object expressions also stay with
their owner. A tightly coupled private implementation helper may remain in its
owner's file when extracting it would expose an implementation detail as a
false package-level concept.

Top-level functions, extension functions, and properties follow semantic
ownership rather than a mechanical one-declaration rule. Tests may keep
private fixtures and scenario helpers beside the test that owns them. These
exceptions do not permit grouping independent production domain types into a
topic-named container file.

The current convergence scope is the production code already changing under
`backend-shared/.../shared/hierarchy/` and
`backend-shared/.../shared/proofloss/`. This decision establishes the default
for future Kotlin production changes; it does not authorize an unrelated
repository-wide file migration.

## Rationale

Same-named, isolated files make a type's ownership, navigation target, and
review boundary explicit. Agents and humans can load, move, diagnose, and
replace one semantic unit without first separating unrelated declarations.
Keeping nested variants with their sealed owner preserves the compiler-visible
closed world and avoids scattering one concept across artificial files.

## Source of truth

| Contract | Source |
| --- | --- |
| Repository coding rule | `AGENTS.md` |
| Kotlin generation and review standard | `.github/instructions/kotlin.instructions.md` |
| Proof-loss refactor shape | `.agents/superpowers/specs/2026-07-11-proof-loss-kotlin-idiom-refactor-design.md` |
| Proof-loss execution steps | `.agents/superpowers/plans/2026-07-11-proof-loss-kotlin-idiom-refactor.md` |
| Current structural regression proof | `backend-shared/src/test/kotlin/io/github/amichne/kast/shared/KotlinFileIsolationTest.kt` |

## Change rule

New or materially edited production Kotlin files follow this rule. If a
top-level production type must share a file, document why it is a private
implementation detail owned by the primary type. Do not create compatibility
aliases or topic-named aggregation files to avoid a file move.

## Validation

Run the structural contract and owning module tests:

```console
./gradlew :backend-shared:test --tests io.github.amichne.kast.shared.KotlinFileIsolationTest
./gradlew :backend-shared:test
kast agent diagnostics --workspace-root "$PWD" --file-path <changed-file>...
git diff --check
```
