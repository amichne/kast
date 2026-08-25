# Delivery receipt progression guide

This directory owns task-specific exact-head receipt progression after KVP-001.

- `Kvp002ReceiptTasks.kt` executes the fixed type-model gates, owns the generated KVP-002 proof
  report, admits KVP-001, and derives KVP-002 completion.
- `Kvp003ReceiptTasks.kt` executes the fixed graph gates, owns the generated KVP-003 proof report,
  admits the complete KVP-002 closure, and derives KVP-003 completion.
Keep gate commands fixed and argument-vector based. Every receipt write must be exact-head checked,
atomically written, read back, and admitted before it can support a later task.
