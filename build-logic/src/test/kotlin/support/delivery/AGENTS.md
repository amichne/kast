# Delivery-program tests

Use JUnit Jupiter through the existing build-logic test contract. Test observable program invariants: exact target identity, derived graph order and waves, terminal reachability, requirement-trace derivation, proof-gate ownership, build-only receipt locations, authority admission, digest-derived authority generation, explicit superseded-authority provenance, portable declared source paths, and rejection of invalid graph structure or authority input.

Run:

```shell
./gradlew -p build-logic test --tests support.delivery.KastVfsPassiveReusedIndexProgramTest
./gradlew -p build-logic test --tests support.delivery.ProgramAuthorityAdmissionTest
./gradlew -p build-logic test --tests support.delivery.ProgramAuthorityGenerationTest
./gradlew -p build-logic test --tests support.delivery.RequirementAuthorityRecoveryTest
./gradlew -p build-logic test --tests support.delivery.ReceiptEvidenceLocationTest
```
