# KVP-015 and KVP-017 READ_EPOCH receipt guide

This directory owns the generated KVP-015 signal ledger and its typed exact-head receipt closure.
It also owns the sibling KVP-017 source-bound read-epoch report and receipt closure, which directly
re-admits KVP-015 only. Its `model/` child independently owns KVP-016 detached-model proof and
KVP-018 hosted-path proof; KVP-018 joins the KVP-016 and KVP-017 completions in a fixed order.

- Keep the report a closed generated-serialization document with canonical bytes, authority
  `READ_EPOCH`, exact IDEA build `262.9437.185`, and the five ordered signal categories.
- Preserve all eleven ordered characterization cases with exactly two samples each. The VFS storm
  has exactly 1,000 root-filtered events in one batch; project-model transitions distinguish
  workspace movement, import start, import completion, and Gradle-root movement. All refresh,
  import, walk, hash, semantic-job, EDT-work, and blocking-wait counts remain zero.
- Use `VirtualFileManager.VFS_CHANGES` as VFS authority. Explicitly reject the constant-zero manager
  counters rather than presenting them as movement evidence.
- Execute only the declared `characterizeEpochNegative` and `characterizeEpoch` Gradle task paths
  as fixed vectors. Reconstruct the complete KVP-014 closure at live HEAD before issuing any gate.
- RED binds its negative, API, class-member, and typed fixture contracts plus the module task
  wiring. GREEN binds the canonical report, positive, API, class-member, and fixture contracts,
  module task wiring, and engineering ledger.
- KVP-017 executes only the fixed contract RED and two-module GREEN selectors. Its canonical
  report proves exact admitted-Project/runtime scope, `SAME`, `MOVED`, and `INCOMPARABLE`
  comparison, the five live signal authorities, finite observation failures, exact event/path and
  cached-model bounds, a bounded 1,000-event VFS batch, and separate zero repository-walk and VFS-
  traversal observations alongside zero refresh, import, hash, scheduling, EDT, and blocking
  effects. The product GREEN selector consumes an independently owned exact-byte report contract.

Raw JSON, process, source, and receipt bytes stay at Gradle boundaries. Expected report and receipt
failures remain finite typed data until a task renders them.
