# Receipt boundary ownership

This directory owns graph-derived task-packet projection, generated JSON decoding/encoding,
bounded legacy-prefix observation, and atomic issuance for delivery receipts.

- `DeliveryReceiptJsonBoundary.kt` preserves the admitted v1 RED/GREEN/completion codec used only by KVP-001..024 legacy progression.
- `LegacyReceiptPrefixBoundary.kt` admits the pinned KVP-024 frontier without reconstructing current-head v1 expectations.
- `TaskProofReceiptJsonBoundary.kt` is the generated v2 `TaskProofReceipt` codec.
- `TaskProofReceiptIssuanceBoundary.kt` reuses content-scoped v2 receipts and enforces exact head only for graph-declared milestone policy.
- `TaskPacketJsonBoundary.kt` projects and re-admits the complete graph-owned atomic task packet.
- `atomic/kvp025/` owns endpoint-retirement proof. `atomic/kvp026/` owns exact-root CLI endpoint
  admission proof, including graph-named JUnit evidence and topological predecessor re-admission.

Raw JSON, filesystem paths, time, and current Git head may appear only at these boundaries. Pass admitted receipt capabilities inward; never pass decoded primitives or silently reinterpret a v1 receipt as v2.

Run:

```shell
./gradlew -p build-logic test --tests support.delivery.DeliveryProofTest
./gradlew -p build-logic test --tests support.delivery.TaskProofReceiptTest
```
