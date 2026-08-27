# Delivery proof-state tests

This directory owns legacy KVP-007/KVP-008 proof-state tests and the KVP-025+ atomic proof protocol
and receipt admission tests.

- `DeliveryReceiptTest.kt` proves exact receipt admission, same-head replay identity,
  generated-codec rejection, and every recomputed-digest invalidation.
- `DeliveryStateTest.kt` proves blocked, ready, invalid, proven, requirement, critical-path, and
  terminal derivation from admitted completion receipts.
- `TaskProofProtocolTest.kt` proves the legacy/atomic seam, graph-derived packet, full KVP-025
  closure, exact-head milestone set, and cross-head-stable content output.
- `TaskProofReceiptTest.kt` proves every v2 content field, self digest, content-scoped replay, and
  exact-head rejection.

Keep the canonical `*DeliveryProof{Negative,}Test` and `*DeliveryState{Negative,}Test` selectors
stable because the typed delivery program binds their exact commands.
