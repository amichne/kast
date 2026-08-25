# KVP-006 gate and receipt guide

This directory owns the canonical 129-gate Gradle registration proof and the exact-head KVP-006
receipt progression.

- `DeliveryGateGraphTasks.kt` generates and decodes the positive and negative KVP-006 reports from
  generated-serializer documents.
- KVP-006 receipt tasks execute fixed argument vectors, directly admit KVP-003 and KVP-005
  completion, bind both reports, and derive completion at one exact Git head.
- Registration must replace the generic KVP-006 placeholders and preserve one registered task for
  every program gate without executing later placeholders.

Keep all expected report and receipt failures finite typed data. Raw JSON and process arguments may
cross only their outer Gradle boundaries.
