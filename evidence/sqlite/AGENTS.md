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
  transaction, and re-admits exact content before granting reuse or read eligibility. Malformed
  persisted topology values, including platform-invalid paths, are corrupt-snapshot data rather
  than storage availability failures.
- Topology v2 gives every symbol a positive snapshot-local row identity derived from compiler
  identity plus exact file and range evidence. Edges reference those exact rows, so declarations
  with equal compiler identities are not collapsed across locations.
- Public traversal opens one request-local relation compiler by re-admitting the exact topology
  content once. Every frontier expansion reads the retained immutable content; no one-hop read may
  reopen or reconstruct the physical snapshot.

## Verification

Run `./gradlew :evidence:sqlite:test`.
