# Delivery-program build policy

This package owns the typed, exact-head delivery program and the Gradle task boundaries that project it into repository artifacts.

## Authorities

- `KastVfsPassiveReusedIndexProgram` composes the canonical program from the foundation, milestone-task, and runtime-graph files.
- `DeliveryProgramModel.kt` owns validation, authority admission, derived waves, the canonical program projection, and the requirement-trace projection.
- `DeliveryProjectionTasks.kt` owns the Gradle filesystem boundaries. Projection generation replaces both projections atomically; verification reads without generation. Authority gates observe Git metadata without process start, admit declared source bytes, and reject incomplete authority inputs.
- `ProgramMain.kt` is the dependency-free projection entry point used by `scripts/verify_bundle.sh`.

Keep each Kotlin source below the repository shape limit. Preserve task order when moving task declarations between milestone files; program and requirement-trace fingerprints must remain derived from the typed authority.

## Focused proof

```shell
./gradlew -p build-logic test --tests support.delivery.KastVfsPassiveReusedIndexProgramTest
./gradlew -p build-logic test --tests support.delivery.ProgramAuthorityAdmissionTest
./gradlew generateKastVfsPassiveProjection verifyKastVfsPassiveProjection
./gradlew verifyKastVfsPassiveAuthorityNegative
scripts/verify_bundle.sh
```
