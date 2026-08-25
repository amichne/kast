# Delivery-program build policy

This package owns the typed, exact-head delivery program and the Gradle task boundaries that project it into repository artifacts.

## Authorities

- `KastVfsPassiveReusedIndexProgram` composes the canonical program from the foundation, milestone-task, and runtime-graph files.
- `model/DeliveryProgramModel.kt` owns program validation, derived waves, the canonical program projection, and the requirement-trace projection.
- `model/ProgramAuthorityModel.kt` owns parsed expectations and authority admission.
- `model/ProgramAuthorityGeneration.kt` binds source IDs to paths only through exact declared digests and returns finite failures for incomplete or ambiguous evidence.
- `tasks/DeliveryProjectionTasks.kt` owns deterministic program and requirement projection tasks.
- `tasks/DeliveryTaskBoundaries.kt` owns process-free Git observation, bounded source reads, SHA-256 observation, atomic writes, and Gradle failure rendering.
- `tasks/ProgramAuthorityJsonBoundary.kt` owns generated serializers for closed authority documents.
- `tasks/ProgramAuthorityTasks.kt` owns authority generation and GREEN verification. Generation writes only the authority ledger and contradiction projection; verification re-observes the head and source bytes before reporting success.
- `tasks/ProgramAuthorityNegativeTask.kt` owns the deterministic KVP-001 RED fixtures.
- `ProgramMain.kt` is the dependency-free projection entry point used by `scripts/verify_bundle.sh`.

Keep each Kotlin source below the repository shape limit. Preserve task order when moving task declarations between milestone files; program and requirement-trace fingerprints must remain derived from the typed authority.

## Focused proof

```shell
./gradlew -p build-logic test --tests support.delivery.KastVfsPassiveReusedIndexProgramTest
./gradlew -p build-logic test --tests support.delivery.ProgramAuthorityAdmissionTest
./gradlew -p build-logic test --tests support.delivery.ProgramAuthorityGenerationTest
./gradlew help --task generateKastVfsPassiveAuthority
./gradlew generateKastVfsPassiveProjection verifyKastVfsPassiveProjection
./gradlew verifyKastVfsPassiveAuthorityNegative
scripts/verify_bundle.sh
```
