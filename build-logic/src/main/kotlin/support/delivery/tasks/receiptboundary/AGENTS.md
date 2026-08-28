# Receipt boundary ownership

This directory owns graph-derived task-packet projection, generated JSON decoding/encoding,
bounded legacy-prefix observation, and atomic issuance for delivery receipts.

- `DeliveryReceiptJsonBoundary.kt` preserves the admitted v1 RED/GREEN/completion codec used only by KVP-001..024 legacy progression.
- `LegacyReceiptPrefixBoundary.kt` admits the pinned KVP-024 frontier without reconstructing current-head v1 expectations.
- `TaskProofReceiptJsonBoundary.kt` is the generated v2 `TaskProofReceipt` codec.
- `TaskProofReceiptIssuanceBoundary.kt` reuses content-scoped v2 receipts and enforces exact head only for graph-declared milestone policy.
- `TaskPacketJsonBoundary.kt` projects and re-admits the complete graph-owned atomic task packet.
- `plugin/kvp011/` owns final hosted-plugin archive/classpath proof. `atomic/kvp025/` owns
  endpoint-retirement proof. `atomic/kvp026/` owns exact-root CLI endpoint
  admission proof, including graph-named JUnit evidence and topological predecessor re-admission.
- `atomic/kvp027/` owns the IDE-only default demand proof, including typed misuse/legal gate
  evidence, successful CLI test observation, KVP-026 re-admission, and content-scoped reuse.
- `atomic/kvp032/` owns the composed static VFS-passive proof over the compiled module graph,
  forbidden JVM effects, IDE-read firewall, and physical transitive plugin classpath.
- `atomic/kvp033/` owns non-cacheable contention, cancellation, movement, VFS-storm, EDT-surface,
  dependency-closure, and content-receipt proof.
- `atomic/kvp034/` owns graph-derived installed metrics, live exact-root CLI acceptance, predecessor
  closure, declared-write enforcement, and the exact-head installed receipt.
- `release/kvp035/` owns the default hosted release's graph-derived packet, misuse fixtures, exact
  asset admission, ready-frontier write scope, predecessor closure, and content receipt.
- `release/kvp036/` owns exact-head retirement of default manifest, archive, store, private-home,
  process-fallback, installer, release, CLI, and documentation authority while preserving an
  explicitly labeled non-default compatibility fixture.

Raw JSON, filesystem paths, time, and current Git head may appear only at these boundaries. Pass admitted receipt capabilities inward; never pass decoded primitives or silently reinterpret a v1 receipt as v2.

Run:

```shell
./gradlew -p build-logic test --tests support.delivery.DeliveryProofTest
./gradlew -p build-logic test --tests support.delivery.TaskProofReceiptTest
```
