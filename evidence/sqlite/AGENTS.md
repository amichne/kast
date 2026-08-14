# Evidence SQLite module guide

`:evidence:sqlite` adapts the existing source-index workspace-generation transaction to the
host-neutral evidence publication SPI. It owns no reconciliation, indexing, semantic admission,
or second publication database.

## Invariants

- Adapt one `WorkspaceGenerationStore` directly into host-neutral begin, prepare, commit, and
  discard capabilities.
- Preserve the source, reference, graph, progress, and publication row in the existing atomic
  source-index transaction.
- Opaque open and prepared capabilities remain bound to the adapter that created them.
- Keep store-specific transaction handles inside this adapter; ordinary consumers receive only
  detached `WorkspacePublicationCommit` evidence.
- Do not add JDBC statements or a parallel schema in this module.

## Verification

Run `./gradlew :evidence:sqlite:test --tests '*SqliteWorkspaceGenerationPublicationTest'`.
