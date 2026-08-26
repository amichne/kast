# Gate and receipt-proof guide

This directory owns the canonical 129-gate Gradle registration proof, the exact-head KVP-006
receipt progression, KVP-007's receipt-invalidation proof, KVP-008's derived-state proof, and
KVP-009's IDE-read firewall receipt closure.

- `DeliveryGateGraphTasks.kt` generates and decodes the positive and negative KVP-006 reports from
  generated-serializer documents.
- KVP-006 receipt tasks execute fixed argument vectors, directly admit KVP-003 and KVP-005
  completion, bind both reports, and derive completion at one exact Git head.
- KVP-007 receipt tasks execute the fixed included-build selectors, directly admit KVP-006,
  generate and decode the exhaustive invalidation report, and derive completion at one exact head.
- `state/` owns KVP-008's generated state report and exact-head receipt progression; `firewall/`
  owns KVP-009 report admission and exact-head progression; `registration/` owns compiled task
  registration through KVP-009.
- Registration must replace generic placeholders through KVP-009 and preserve one registered task
  for every program gate without executing later placeholders.

Keep all expected report and receipt failures finite typed data. Raw JSON and process arguments may
cross only their outer Gradle boundaries.
