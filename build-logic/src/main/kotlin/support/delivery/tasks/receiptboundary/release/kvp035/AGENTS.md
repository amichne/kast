# KVP-035 atomic proof policy

This package owns KVP-035 task-packet generation, predecessor admission, release-report
refinement, ready-frontier write-scope enforcement, and its one content-scoped receipt.

- Generate every task and proof field from the canonical Kotlin delivery graph.
- Admit KVP-034 only at the currently observed exact head; KVP-011 remains content-scoped.
- Select implementation commits through the graph-owned `default-hosted-release` batch frontier
  and its exclusive `distribution/release` anchor.
- Accept only a canonical two-payload control-plus-plugin report at or below 80 MiB.
- Exercise all graph-named misuse and legal commands before receipt issuance.

Run `./gradlew proveKVP035`.
