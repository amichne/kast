# Delivery-program build policy

This package owns the typed, exact-head delivery program and the Gradle task boundaries that project it into repository artifacts.

## Authorities

- `KastVfsPassiveReusedIndexProgram` composes and finitely admits the canonical program from the
  foundation, milestone-task, and runtime-graph files. Admission proves complete contracts, closed
  ownership/classification references, deterministic graph order and waves, and one terminal sink.
- `model/DeliveryProgramModel.kt` owns program validation, derived waves, the canonical program projection, and the requirement-trace projection.
- `model/ProgramAuthorityModel.kt` owns parsed expectations and authority admission.
- `model/ProgramAuthorityGeneration.kt` binds source IDs to paths only through exact declared digests and returns finite failures for incomplete or ambiguous evidence.
- `model/DeliveryReceipt.kt` and `model/DeliveryReceiptRefinement.kt` own closed receipt identities,
  failures, canonical payload digests, issuance, and admission.
- `model/projection/` owns the KVP-005 five-artifact generation, generated schema documents,
  canonical JSON admission, and finite projection failures.
- `tasks/projection/` owns the bounded KVP-005 generation, verification, negative-fixture, and proof
  report effects.
- `model/DeliveryGateGraph.kt` owns KVP-006's exact gate-input, unique-output, and registered-task
  bijection. `tasks/receipt/gate/` owns its generated proof reports and receipt progression.
- `tasks/DeliveryTaskBoundaries.kt` owns process-free Git observation, bounded source reads, SHA-256 observation, atomic writes, and Gradle failure rendering.
- `tasks/ProgramAuthorityJsonBoundary.kt` owns generated serializers for closed authority documents.
- `tasks/ProgramAuthorityTasks.kt` owns authority generation and GREEN verification. Generation
  writes only the authority ledger and contradiction projection under `build/reports/delivery`;
  verification re-observes the head and source bytes before reporting success. These exact-head
  artifacts are build evidence and must not be checked in.
- `tasks/ProgramAuthorityNegativeTask.kt` owns the deterministic KVP-001 RED fixtures.
- `tasks/DeliveryReceiptJsonBoundary.kt` owns the generated receipt serializer.
- `tasks/Kvp001ReceiptTasks.kt` and its support file own the typed root-task recorder and completion
  bootstrap. KVP-001 GREEN consumes the admitted RED receipt; completion consumes both gate
  receipts. The later KVP-007 task generalizes and proves this boundary for the remaining graph.
- `tasks/receipt/` owns typed post-authority progression. KVP-002 through KVP-007 execute their exact
  included-build gates without shell parsing, emit generated proof reports, admit the complete
  predecessor closure, and derive exact-head completion receipts.
- `ProgramMain.kt` is the dependency-free projection entry point used by `scripts/verify_bundle.sh`.

Gate and completion receipt paths are part of the typed program, but their live evidence belongs
under `build/reports/delivery/receipts`. Never place receipt evidence in tracked projection paths:
the receipt's bytes would change the exact head it claims to bind.

Keep each Kotlin source below the repository shape limit. Preserve task order when moving task declarations between milestone files; program and requirement-trace fingerprints must remain derived from the typed authority.
M0 delivery model writes belong under `model/`; focused tests remain under the delivery test package.

## Focused proof

```shell
./gradlew -p build-logic test --tests support.delivery.KastVfsPassiveReusedIndexProgramTest
./gradlew -p build-logic test --tests support.delivery.KastVfsPassiveProgramNegativeTest
./gradlew -p build-logic test --tests support.delivery.ProgramAuthorityAdmissionTest
./gradlew -p build-logic test --tests support.delivery.ProgramAuthorityGenerationTest
./gradlew -p build-logic test --tests support.delivery.ReceiptEvidenceLocationTest
./gradlew -p build-logic test --tests support.delivery.DeliveryReceiptTest
./gradlew -p build-logic test --tests support.delivery.DeliveryTaskOwnershipTest
./gradlew help --task generateKastVfsPassiveAuthority
./gradlew generateKastVfsPassiveProjection verifyKastVfsPassiveProjection
./gradlew verifyKastVfsPassiveAuthorityNegative
./gradlew verifyKVP001CompletionReceipt
./gradlew verifyKVP002CompletionReceipt
./gradlew verifyKVP003CompletionReceipt
./gradlew verifyKVP004CompletionReceipt
./gradlew verifyKVP005CompletionReceipt
./gradlew verifyKVP006CompletionReceipt
./gradlew verifyKVP007CompletionReceipt
./gradlew verifyKastVfsPassiveGateGraphNegative verifyKastVfsPassiveGateGraph
scripts/verify_bundle.sh
```
