<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-11 | hash: 3426c63fec45 -->

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

- [src/main/kotlin/kast.kotlin-quality.gradle.kts](src/main/kotlin/kast.kotlin-quality.gradle.kts) - shared formatting, Detekt, and structural gates.
- [src/main/kotlin/conventions/KotlinFileLengthTask.kt](src/main/kotlin/conventions/KotlinFileLengthTask.kt) - production/test file-length enforcement.
- [src/main/kotlin/conventions/KotlinFileLengthBaseline.kt](src/main/kotlin/conventions/KotlinFileLengthBaseline.kt) - validated per-file ceilings for existing oversized sources.
- [src/main/kotlin/conventions/JsonContractVerification.kt](src/main/kotlin/conventions/JsonContractVerification.kt) - JSON syntax guard registration and root verification-gate dependencies.
- [src/main/kotlin/conventions/VerifyJsonContractsTask.kt](src/main/kotlin/conventions/VerifyJsonContractsTask.kt) - typed scan request and isolated parser process boundary.

## Subdirectories

- `src/main/kotlin/conventions` - serialization, publication, and verification conventions.
- `src/main/kotlin/conventions/jsoncontracts` - JSON syntax scanner, typed findings, and exact baseline admission.
- `src/main/kotlin/kast/role` - role-specific dependency/effect policy plugins.
- `src/main/kotlin/support/architecture` - scanners, policy, validation, and projections.
- `src/main/kotlin/support/tasks` - generated control and documentation tasks.
- `src/test` - policy and plugin evidence.

## Entry Points

- Included from root `settings.gradle.kts` with `includeBuild("build-logic")`.
- Run `./gradlew verifyJsonContracts` from the repository root; `check` and `productBuildGate` require it. See the [JSON guard contract](../config/json-contracts/README.md) for its syntax-evidence limits and baseline policy.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/architecture.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- For formatting or lint failures, start with `kast.kotlin-quality.gradle.kts`, then the reported rule or file-length task. Schema-generated Kotlin remains owned by its generator.
- For existing structural debt, see [baseline policy](../config/README.md). Checks reject new findings and growth beyond recorded file ceilings; they never regenerate allowances.
- For a dependency-direction failure, start with the applied `kast/role` convention, then the policy validator.
- For generated artifacts or publication, start in `src/main/kotlin/support/tasks` or `src/main/kotlin/support/publishing`.
