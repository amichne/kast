# Runtime composition module guide

`:runtime:composition` is the sole owner of the complete target implementation graph and the
nominal association between each canonical operation and its direct target service boundary.

## Dependency boundary

- Production may depend on every target module except `:cli` and `:indexer`.
- Do not depend on legacy aggregates, aggregate backend types, compatibility routes, fallbacks,
  service locators, or raw handler maps.
- Platform and persistence effects remain behind the target adapter and service boundaries; this
  module only constructs and binds them.
- Export only composition-owned runtime capabilities. Target implementation dependencies remain
  internal.

## Contract invariants

- Exactly eleven nominal operation slots exist, in canonical order.
- Each slot receives its operation-specific target service boundary directly.
- A binding factory result is admitted only when its canonical operation matches the nominal slot.
- Runtime-server construction remains closed data; missing, duplicate, or mismatched bindings
  cannot produce a runnable composition.

## Verification ladder

1. Run `./gradlew :runtime:composition:test --tests '*KastRuntimeCompositionTest'`.
2. Run `./gradlew :runtime:composition:test :runtime:server:test verifyKastModuleGraph`.
3. Run `./gradlew verifyNoLegacyArchitecture verifyKastModuleGraph build` for full repository proof.
