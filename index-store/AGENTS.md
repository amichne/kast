# Index store agent guide

`index-store` owns the backend-private SQLite source index, workspace cache
persistence, and headless/indexer hydration APIs shared across Kast runtimes.
Under `.agents/adr/0023-signed-idea-plugin-distribution-and-runtime-authority.md`,
the database is not a public or cross-process compatibility surface. New CLI
and agent reads must use typed `analysis-api`/`analysis-server` operations.

## Ownership

Keep this unit focused on storage concerns and schema continuity.

- Keep SQLite schema, migrations, interning codecs, and hydration helpers here.
  `packaging/homebrew/release-state.json` owns the checked-in schema version;
  generated Kotlin/Rust `SOURCE_INDEX_SCHEMA_VERSION` values, table layouts,
  and query columns must stay aligned. Version 8 is required for workspace-file
  project/source-set and package-provenance reads; version 7 must reset/rebuild.
- Treat `schema_version.generation` as the source-index change token. Increment
  it in the same write transaction as candidate-bearing tables, module index
  progress, or pending-update applied state so read-only consumers can prove a
  stable snapshot without changing the schema.
- Keep `module_index_progress` and unapplied `pending_updates` truthful. A
  readable row set is not complete index evidence while the initialized
  progress set is empty/incomplete, indexed counts differ from totals, or
  updates remain pending.
- Persist project-model Gradle ownership only as non-null association rows in
  `file_gradle_projects`, produced from linked Gradle model evidence. The build
  root is workspace-relative, and each file may retain multiple owners, so root
  and included builds with the same project path remain distinct. Legacy
  `file_metadata.module_path` is an unqualified symbol/metrics label; never
  promote an IDEA fallback from it into Gradle identity.
- Persist structured Gradle source-set evidence only in non-null
  `file_gradle_source_sets` rows keyed by build root, project path, and source-set
  name. Legacy `file_metadata.source_set` is an unproven label and cannot
  satisfy workspace source-set filters.
- Persist package provenance explicitly. `PROVEN_ROOT`, `PROVEN_NAMED`, and
  `UNPROVEN` must agree with `package_fq_id` constraints; nullable or failed
  parser output is `UNPROVEN` with a typed reason, never root. Proven states
  cannot carry an unproven reason. Canonical package names come from
  Kotlin PSI/compiler evidence, including escaped/backticked/Unicode names.
- Bootstrap `sqlite-jdbc` inside this module before `DriverManager` access.
  IDEA and other plugin classloaders require explicit driver registration.
- Keep this unit runtime-agnostic. `backend-shared` may use IntelliJ/Kotlin PSI,
  but it must convert package semantics to host-neutral
  `IndexedPackageEvidence` before constructing `FileIndexUpdate`; no PSI type
  crosses into this module. CLI process management and JSON-RPC transport code
  live in their CLI and server owners.
- Treat schema resets, additive migrations, and cache hydration changes as
  contract-sensitive. Operational source-index reads belong to the active
  backend and are exposed through typed server operations. Existing direct Rust
  readers are migration targets; do not add new cross-process SQLite reads.
  Backend-hosted Kotlin may read SQLite for semantic operations, headless
  hydration, or targeted indexer/cache behavior.
- Return paged index evidence and its generation atomically under the same
  store lock. Every committed transition that can change indexed declarations,
  references, manifests, or reconciliation state must advance the generation;
  consumers use it to reject stale continuation pages.
- The production `declarations` key is `(fq_id, prefix_id, filename)` and writes
  replace that row. It cannot prove uniqueness of same-FQ overloads in one
  file. Never use an FQ declaration-row count as callable overload proof.
- `symbol_references.targetPath` and `targetOffset` are optional evidence.
  Exact indexed relationship reads require the selected canonical target path
  and one non-null target offset; FQ-only or null/mixed target anchors must fail
  closed or fall back before a continuation source is bound.

## Verification

Prove storage changes here before relying on higher-level runtime tests.

- Run `./gradlew :index-store:test`.
- For page/generation changes, also run `./gradlew :backend-idea:test` to prove
  production continuation invalidation rather than only store-local behavior.
- For generation/progress/pending changes, prove rollback atomicity and
  before/after generation behavior in `SqliteSourceIndexStoreTest`.
- For build-qualified identity changes, prove schema migration/reset,
  root-versus-included-build round trips, multiple owners per file, identical
  project paths in different builds, malformed identity rejection, legacy
  fallback isolation, and transactional generation change.
- For schema/provenance changes, prove release-state/Kotlin/Rust version
  alignment, version-7 rejection/reset, required version-8 structures, a custom
  `integrationTest` source root, and no null-to-root package collapse.
- If you change schema bootstrap, connection setup, or hydration reads, exercise
  `SqliteSourceIndexStoreTest` and the affected headless/indexer tests.
- Final acceptance for the cross-module workspace discovery contract also runs
  `./gradlew test` and `./gradlew buildIdeaPlugin`.
- Exact-reference and impact changes also require production-store overload,
  null-offset, and target-anchor regression cases in
  `SqliteSourceIndexStoreTest`.
