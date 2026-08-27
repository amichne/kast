# Delivery-program build policy

This package owns the typed delivery program and the Gradle task boundaries that project it into repository artifacts and content-scoped or milestone-exact proof receipts.

## Authorities

- `KastVfsPassiveReusedIndexProgram` composes and finitely admits the canonical program from the
  foundation, milestone-task, and runtime-graph files. Admission proves complete contracts, closed
  ownership/classification references, deterministic graph order and waves, and one terminal sink.
- `model/DeliveryProgramModel.kt` owns checked identities, task/program structure, and graph validation. `model/projection/ValidatedProgramProjection.kt` owns derived waves, task packets, and deterministic program/requirement projection.
- `model/ProgramAuthorityModel.kt` owns parsed expectations and authority admission.
- `model/ProgramAuthorityGeneration.kt` binds source IDs to paths only through exact declared digests and returns finite failures for incomplete or ambiguous evidence.
- `model/proof/` owns the preserved legacy receipt model, pinned KVP-024 frontier, KVP-025+
  atomic proof protocol, and v2 content-scoped/exact-head receipt admission.
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
- `tasks/receiptboundary/` owns generated v1/v2 receipt codecs, the task-packet codec, pinned-prefix
  observation, and atomic receipt issuance. Its `atomic/kvp011/` and `atomic/kvp025/` through
  `atomic/kvp034/` children own their sole graph-derived task proofs; each revalidates its admitted
  predecessor closure before using the last task-owned implementation checkpoint as the next
  write-scope baseline. KVP-031 additionally binds its receipt to one exact head.
- `tasks/Kvp001ReceiptTasks.kt` and its support file own the typed root-task recorder and completion
  bootstrap. KVP-001 GREEN consumes the admitted RED receipt; completion consumes both gate
  receipts. The later KVP-007 task generalizes and proves this boundary for the remaining graph.
- `tasks/receipt/` owns typed post-authority progression. KVP-002 through KVP-010 and KVP-012
  through KVP-020 execute their exact included-build gates without shell parsing, emit generated
  proof reports, admit the complete predecessor closure, and derive exact-head completion receipts.
  KVP-012 directly preserves KVP-002 and KVP-010; KVP-013 independently preserves KVP-005 and
  KVP-012; KVP-014 independently preserves KVP-009 and KVP-012; KVP-015 independently preserves
  KVP-014 and binds the supported-build epoch-signal ledger; KVP-016 preserves both KVP-014 and
  KVP-015 and binds the detached existing-Project model contract; KVP-017 independently preserves
  KVP-015 and binds the source-bound live project-read epoch contract and report; KVP-018 preserves
  both sibling completions and re-observes the hosted class/runtime closure before report
  admission; KVP-019 independently re-admits KVP-017/KVP-018 and binds the canonical freshness
  report; KVP-020 independently re-admits KVP-014/KVP-019 and binds the bounded single-flight
  controller, exact transition projection, and zero forbidden work consumed by both selectors.
- `ProgramMain.kt` is the dependency-free projection entry point used by `scripts/verify_bundle.sh`.
- KVP-011 is a late M3 layout gate. It consumes the completed KVP-010 package split, KVP-025
  endpoint lifecycle, and KVP-031 four-operation read path before KVP-032 scans the complete hosted
  graph. KVP-012 consumes KVP-010 identity directly so compatibility work does not depend on the
  not-yet-built read-only payload.
- KVP-033 reruns exact production-behavior selectors in two non-cacheable test processes after
  KVP-032 and binds their physical JUnit evidence into one zero-effect dynamic receipt.
- KVP-034 invokes only the installed CLI against the live exact-root hosted endpoint, combines
  direct system observations with admitted KVP-032/KVP-033 proof authority, and requires endpoint
  retirement before issuing its exact-head receipt.

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
./gradlew verifyKVP008CompletionReceipt
./gradlew verifyKVP009CompletionReceipt
./gradlew verifyKVP010CompletionReceipt
./gradlew verifyKVP012CompletionReceipt
./gradlew verifyKVP013CompletionReceipt
./gradlew verifyKVP014CompletionReceipt
./gradlew verifyKVP015CompletionReceipt
./gradlew verifyKVP016CompletionReceipt
./gradlew verifyKVP017CompletionReceipt
./gradlew verifyKVP018CompletionReceipt
./gradlew verifyKVP019CompletionReceipt
./gradlew verifyKVP020CompletionReceipt
./gradlew proveKVP025
./gradlew proveKVP026
./gradlew proveKVP027
./gradlew verifyKastVfsPassiveGateGraphNegative verifyKastVfsPassiveGateGraph
scripts/verify_bundle.sh
```
