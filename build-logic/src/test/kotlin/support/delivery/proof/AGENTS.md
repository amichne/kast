# Delivery proof-state tests

This directory owns legacy KVP-007/KVP-008 proof-state tests and the KVP-025+ atomic proof protocol
and receipt admission tests.

- `DeliveryReceiptTest.kt` proves exact receipt admission, same-head replay identity,
  generated-codec rejection, and every recomputed-digest invalidation.
- `DeliveryStateTest.kt` proves blocked, ready, invalid, proven, requirement, critical-path, and
  terminal derivation from admitted completion receipts.
- `TaskProofProtocolTest.kt` proves the legacy/atomic seam, graph-derived packet, full KVP-025
  closure including `gradlew`, closed write-scope admission, exact-head milestone set, and
  cross-head-stable content output. It also keeps KVP-026 on the admitted KVP-025 atomic frontier
  instead of an absent legacy completion.
- `TaskProofReceiptTest.kt` proves every v2 content field, self digest, content-scoped replay, and
  exact-head rejection.
- `Kvp038ImplementationBaselineTest.kt` and `Kvp038LegacyDependencyAdmissionTest.kt` prove the
  clean-checkout implementation baseline and its admitted legacy dependency seam.

Keep the canonical `*DeliveryProof{Negative,}Test` and `*DeliveryState{Negative,}Test` selectors
stable because the typed delivery program binds their exact commands.
