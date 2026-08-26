# Delivery receipt progression guide

This directory owns task-specific exact-head receipt progression after KVP-001.

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
  KVP-001 through KVP-010 and KVP-012; the convention script retains only projection, authority, and
  placeholder orchestration.
- `gate/` owns KVP-006's positive and negative structural reports over all 129 receipt tasks and its
  exact-head receipt progression, KVP-007 receipt invalidation, KVP-008 delivery-state progression,
  KVP-009's IDE-read firewall receipt closure, and KVP-010's independently revalidated standalone
  plugin artifact receipt closure. Its plugin child also owns KVP-012's exact compatibility-report
  admission and two-predecessor receipt closure. Structural validation never executes registered gates.
Keep gate commands fixed and argument-vector based. Every receipt write must be exact-head checked,
atomically written, read back, and admitted before it can support a later task.
