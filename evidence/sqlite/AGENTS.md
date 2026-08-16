# Evidence SQLite module guide

`:evidence:sqlite` owns physical SQLite persistence for host-neutral evidence. It adapts the
existing source-index workspace-generation transaction and owns the clean-slate mutation recovery
journal. It owns no reconciliation, indexing, semantic admission, or source mutation.

## Invariants

- Adapt one `WorkspaceGenerationStore` directly into host-neutral begin, prepare, commit, and
  discard capabilities.
- Preserve the source, reference, graph, progress, and publication row in the existing atomic
  source-index transaction.
- Opaque open and prepared capabilities remain bound to the adapter that created them.
- Keep store-specific transaction handles inside this adapter; ordinary consumers receive only
  detached `WorkspacePublicationCommit` evidence.
- Mutation recovery transitions are atomic, compare exact prior record digests, and preserve the
  complete pre-write image, applied write set, terminal state, and tamper-evident plan binding.
- JDBC and the recovery schema remain confined to this module.

## Verification

Run `./gradlew :evidence:sqlite:test`.
