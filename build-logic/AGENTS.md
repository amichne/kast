<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-11 | hash: 46374a803fb9 -->

# build-logic

## Purpose

Defines reusable Gradle conventions, module roles, publication behavior, generated contracts, and mechanical architecture policy.

## Key Files

- [settings.gradle.kts](settings.gradle.kts) - included-build plugin management.
- [src/main/kotlin/kast.architecture.gradle.kts](src/main/kotlin/kast.architecture.gradle.kts) - architecture verification plugin.
- [src/main/kotlin/kast.kotlin-library.gradle.kts](src/main/kotlin/kast.kotlin-library.gradle.kts) - common Kotlin library convention.
- [src/main/kotlin/kast.runtime-app.gradle.kts](src/main/kotlin/kast.runtime-app.gradle.kts) - runtime application convention.
- [src/main/kotlin/support/architecture/ArchitectureModel.kt](src/main/kotlin/support/architecture/ArchitectureModel.kt) - architecture model.
- [src/main/kotlin/support/architecture/policy/KastCleanSlateModules.kt](src/main/kotlin/support/architecture/policy/KastCleanSlateModules.kt) - admitted module topology.

## Subdirectories

- `src/main/kotlin/conventions` - serialization and publication conventions.
- `src/main/kotlin/kast/role` - role-specific dependency/effect policy plugins.
- `src/main/kotlin/support/architecture` - scanners, policy, validation, and projections.
- `src/main/kotlin/support/tasks` - generated control and documentation tasks.
- `src/test` - policy and plugin evidence.

## Entry Points

- Included from root `settings.gradle.kts` with `includeBuild("build-logic")`.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/architecture.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- For a dependency-direction failure, start with the applied `kast/role` convention, then the policy validator.
- For generated artifacts or publication, start in `src/main/kotlin/support/tasks` or `src/main/kotlin/support/publishing`.
