# KVP-025 atomic proof ownership

This directory owns KVP-025's graph-derived packet, named misuse/legal test evidence, scoped Git
delta admission, deterministic proof report, and single v2 completion receipt.

- Derive every task field and selector from the admitted Kotlin graph packet.
- Admit only the pinned KVP-024 legacy frontier receipt; do not reconstruct or rerun it.
- Hash only declared tracked input roots and reject dirty relevant inputs.
- Bind the KVP-025-scoped delta after KVP-024 without treating a delivery-batch commit as a task
  boundary; nonconflicting paths owned by other tasks remain outside the transition.
- Decide reuse before test execution. Re-admit unchanged report/receipt bytes, or execute the
  graph-selected suite and atomically replace both when the relevant closure changed.

Run `./gradlew proveKVP025` as the sole public proof command.
