# Protocol contract module guide

`:protocol:contract` owns the permanent public operation identities and the type/schema bindings
shared by the registry, wire transport, runtime server, and CLI.

## Dependency boundary

- Production depends only on `:kernel`.
- Do not import serialization, transport, runtime, service, adapter, platform, filesystem, Gradle,
  JDBC, or IntelliJ types.
- Primitive schema and operation text may enter only through the documented refinement functions.

## Contract invariants

- `CanonicalOperation` contains exactly the eleven public operations from the clean-slate plan.
- Operation request, result, qualification, and rejection values retain distinct marker types.
- Every type binding includes all four Kotlin types and one refined schema identity.
- Unknown operation identity and invalid schema identity remain closed failures.

## Verification ladder

1. Run `./gradlew :protocol:registry:test :protocol:wire:test`.
2. Run `./gradlew verifyKastArchitecture --configuration-cache`.
