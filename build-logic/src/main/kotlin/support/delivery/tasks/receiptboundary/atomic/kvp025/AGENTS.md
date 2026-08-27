# KVP-025 atomic proof ownership

This directory owns KVP-025's graph-derived packet, named misuse/legal test evidence, scoped Git
delta admission, deterministic proof report, and single v2 completion receipt.

- Derive every task field and selector from the admitted Kotlin graph packet.
- Admit only the pinned KVP-024 legacy frontier receipt; do not reconstruct or rerun it.
- Hash only declared tracked input roots and reject dirty relevant inputs.
- Observe every changed path after KVP-024. Preserve the already-admitted implementation segment
  through a self-digested prior KVP-025 receipt, then append only later commits containing
  KVP-025-owned paths; never use a Git pathspec to hide the full observation.
- Preserve one successful named enforcement case for every graph-declared forbidden-work fact;
  suite success alone is not forbidden-work evidence.
- Decide reuse before test execution. Re-admit unchanged report/receipt bytes, or execute the
  graph-selected suite and atomically replace both when the relevant closure changed.

Run `./gradlew proveKVP025` as the sole public proof command.
