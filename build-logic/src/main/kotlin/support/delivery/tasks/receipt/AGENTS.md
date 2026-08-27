# Delivery receipt progression guide

This directory owns task-specific legacy exact-head receipt progression through KVP-024.

- `ReceiptIssuanceBoundary.kt` is the common reuse-or-replace effect boundary for every receipt.
  It reuses only fully admitted same-expectation bytes and fails closed on unsafe file states.
- `Kvp002ReceiptTasks.kt` executes the fixed type-model gates, owns the generated KVP-002 proof
  report, admits KVP-001, and derives KVP-002 completion.
- `Kvp003ReceiptTasks.kt` executes the fixed graph gates, owns the generated KVP-003 proof report,
  admits the complete KVP-002 closure, and derives KVP-003 completion.
- `Kvp004ReceiptTasks.kt` and its progression file execute the fixed canonical-program gates, own
  the generated KVP-004 proof report, preserve both direct predecessor completions, and derive
  KVP-004 completion.
- `Kvp005ReceiptTasks.kt` and its progression file execute the fixed projection gates, decode the
  generated KVP-005 report, bind all five projected artifacts, admit KVP-004 completion, and derive
  KVP-005 completion.
- `gate/registration/ReceiptProgressionRegistration.kt` is the Gradle registration authority for
  KVP-001 through KVP-010 and KVP-012 through KVP-018; the convention script retains only projection,
  authority, and placeholder orchestration.
- `gate/` owns KVP-006's positive and negative structural reports over the legacy portion of the
  canonical 91-gate graph and its
  exact-head receipt progression, KVP-007 receipt invalidation, KVP-008 delivery-state progression,
  KVP-009's IDE-read firewall receipt closure, and KVP-010's independently revalidated standalone
  plugin artifact receipt closure. Its plugin child owns KVP-012's compatibility report, its endpoint
  child admits KVP-013's descriptor closure, and its project child admits KVP-014's existing-Project
  report plus independent KVP-009 and KVP-012 closures. The project's `epoch/` child admits
  KVP-015's supported-build signal ledger and the complete KVP-014 closure. The same `epoch/`
  owner admits KVP-017's source-bound read-epoch report directly from KVP-015; its `model/` child
  independently admits KVP-016's detached-model report and preserves KVP-014 and KVP-015.
  That model owner also admits KVP-018 from the complete compiled-class/runtime closure and both
  semantic KVP-016/KVP-017 completion digests.
  Structural validation never executes registered gates.
Keep legacy gate commands fixed and argument-vector based. Every v1 receipt write must be exact-head checked,
atomically written, read back, and admitted before it can support a later task.
