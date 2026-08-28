# Delivery proof model ownership

This directory owns legacy v1 receipt admission, the pinned KVP-024 migration frontier, the
KVP-025+ atomic proof protocol, and v2 content-scoped/exact-head receipt refinement.

- Preserve KVP-001..024 as legacy receipt data; never reinterpret it as v2.
- Derive atomic commands, cases, dependencies, and head policy from `TaskNode`.
- Admit v2 reuse by complete relevant-input closure. Require live exact head only for graph-named
  exact-head milestones.
- Keep receipt parsing and expected mismatch as closed typed data.

Run `./gradlew -p build-logic test --tests support.delivery.TaskProofProtocolTest --tests support.delivery.TaskProofReceiptTest`.
