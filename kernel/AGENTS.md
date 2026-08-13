# Kernel module guide

`:kernel` is the dependency-free host-neutral base for the typed operation architecture. It owns only permanent operation and capability identities, bounded resource budgets, generation-bound evidence envelopes, refinement results, and the closed semantic outcome family.

## Module map

- `Refinement.kt` owns the generic closed boundary transition result used by kernel parsers.
- `KernelIdentity.kt` owns canonical permanent operation and capability identifiers.
- `ResourceBudget.kt` owns positive result, work-unit, and elapsed-time limits and their aggregate budget.
- `EvidenceEnvelope.kt` owns non-negative evidence generation and binds successful payloads to an operation and generation.
- `OperationOutcome.kt` owns the exhaustive Complete, Qualified, and Rejected semantic outcome states.

## Dependency boundary

- Production has no project or external-library dependency. Kotlin/JDK runtime types are the only implementation substrate.
- Do not import IntelliJ, Gradle, JDBC, filesystem-write, process-control, transport, JSON-RPC, serialization, or legacy `analysis-api` types.
- This module owns primitives shared across multiple final architecture modules. Feature identities, effects, costs, scopes, blockers, handlers, and host capabilities stay with their narrower owner.

## Contract invariants

- Raw strings and numeric limits enter only through documented proof transitions that return `Refinement<Strong, Failure>` with finite failure enums.
- Permanent IDs are lowercase dot-separated identifiers and are never normalized or reconstructed from display data.
- Resource budgets are finite and strictly positive. Do not add zero, negative, optional, or sentinel limits.
- Successful outcomes always retain their `EvidenceEnvelope`; qualification and rejection are distinct closed states, never flags or nullable fields.
- Kernel values are immutable, deterministic data. No I/O, time reads, randomness, mutation, global registry, or service lookup belongs here.

## Verification ladder

1. Run `./gradlew :kernel:test --tests io.github.amichne.kast.kernel.KernelPrimitivesTest`.
2. Run `./gradlew :kernel:test`.
3. Run `./gradlew verifyKastArchitecture --configuration-cache` to prove role, dependency, and effect boundaries.
4. Run direct consumers after a public kernel contract changes.
