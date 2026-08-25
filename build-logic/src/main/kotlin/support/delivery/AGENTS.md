# Delivery-program build policy

This package owns the typed, exact-head delivery program and the Gradle task boundaries that project it into repository artifacts.

## Authorities

- `KastVfsPassiveReusedIndexProgram` composes the canonical program from the foundation, milestone-task, and runtime-graph files.
- `DeliveryProgramModel.kt` owns validation, derived waves, the canonical program projection, and the requirement-trace projection.
- `DeliveryProjectionTasks.kt` is the configuration-cache-compatible filesystem boundary. Generation replaces both projections atomically; verification reads both and never generates them.
- `ProgramMain.kt` is the dependency-free projection entry point used by `scripts/verify_bundle.sh`.

Keep each Kotlin source below the repository shape limit. Preserve task order when moving task declarations between milestone files; program and requirement-trace fingerprints must remain derived from the typed authority.

## Focused proof

```shell
./gradlew -p build-logic test --tests support.delivery.KastVfsPassiveReusedIndexProgramTest
./gradlew generateKastVfsPassiveProjection verifyKastVfsPassiveProjection
scripts/verify_bundle.sh
```
