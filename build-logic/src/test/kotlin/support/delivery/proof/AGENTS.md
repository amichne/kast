# Delivery proof-state tests

This directory owns the focused KVP-007 receipt-binding and KVP-008 derived-state test selectors.

- `DeliveryReceiptTest.kt` proves exact receipt admission, generated-codec rejection, and every
  recomputed-digest invalidation.
- `DeliveryStateTest.kt` proves blocked, ready, invalid, proven, requirement, critical-path, and
  terminal derivation from admitted completion receipts.

Keep the canonical `*DeliveryProof{Negative,}Test` and `*DeliveryState{Negative,}Test` selectors
stable because the typed delivery program binds their exact commands.
