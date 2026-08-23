# Evidence SQLite module guide

`:evidence:sqlite` owns physical SQLite persistence for host-neutral evidence, including canonical
workspace publication, durable topology snapshots, and the mutation recovery journal. It owns no
reconciliation, indexing, semantic admission, or source mutation.

## Invariants

- Own one direct SQLite begin, prepare, commit-or-discard transaction for workspace publication.
- Preserve the prior durable generation when preparation or commit fails.
- Opaque open and prepared capabilities remain bound to the adapter that created them.
- Keep JDBC transaction handles inside this adapter; ordinary consumers receive only detached
  `WorkspacePublicationCommit` evidence.
- Mutation recovery transitions are atomic, compare exact prior record digests, and preserve the
  complete pre-write image, applied write set, terminal state, and tamper-evident plan binding.
- JDBC and the recovery schema remain confined to this module.
- Topology publication admits only complete generation values, commits manifest and content in one
  transaction, and re-admits exact content before granting reuse or read eligibility.

## Verification

Run `./gradlew :evidence:sqlite:test`.
