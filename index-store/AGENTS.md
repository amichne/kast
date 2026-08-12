# Index store module guide

`index-store` owns the runtime-agnostic persistence model for source,
relationship, semantic-graph, workspace-generation, repository-snapshot, and
worktree-overlay evidence. It publishes `kast-index-store`. The database is an
internal cross-process contract, not a public user API.

## Module map

- `api/index` owns workspace-relative source paths, file updates, stage state,
  compiler-proven package evidence, and build-qualified Gradle project/source-
  set identities.
- `api/reference` owns declarations, exact reference targets, generated/indexed
  pages, edge kinds, and `SourceIndexGeneration`.
- `api/graph` and `api/stage` own semantic-graph updates/snapshots and durable
  per-file stage transitions.
- `indexing` contains reference-index orchestration independent of a concrete
  compiler host.
- `store/SqliteSourceIndexStore.kt` is the façade. `store/sqlite` partitions
  inventory, lifecycle/transactions, overlays, pending work, references,
  schema, semantic graph, and stage storage.
- `store/codec` owns path and FQ-name interning; `store/jdbc` owns explicit
  SQLite driver bootstrap.
- `snapshot` owns repository snapshot identity, manifests, publication,
  retention, content shards, workspace-generation typestate, and overlay
  selection.
- `src/test` is organized around the same storage owners: source, stage,
  schema, lifecycle, overlay, semantic graph, snapshot, and generation.

## Dependency boundary

- The module depends on `analysis-api` for normalized workspace, path, query,
  and result types and on `sqlite-jdbc` for persistence.
- It must not import IntelliJ, Kotlin PSI/compiler, server transport, CLI
  process-management, or foreground IDE types. Hosts convert evidence to
  `FileIndexUpdate`, `IndexedPackageEvidence`, graph, stage, and reference
  values before calling this module.
- `analysis-server` declares an implementation dependency, but storage
  ownership remains here. `indexer` is the read/write host; Rust readers use
  the same schema through the CLI-owned workspace path.
- Resolve the database through `WorkspaceIdentity`, whose roots come from the
  active CLI receipt. No backend or plugin may derive a competing database
  location.
- `cli-rs/protocol/source-index-schema-version.txt` is the sole checked-in
  schema-version authority. Build logic generates the Kotlin constant and
  `cli-rs/build.rs` generates the Rust constant from that file.

## Storage invariants

- `SqliteSourceIndexStoreAccess.READ_ONLY` must never obtain a writer lease,
  initialize schema, mutate interning tables, attach writable repository state,
  or repair data. Read/write access has one exact database writer lease.
- Register `sqlite-jdbc` inside this module before `DriverManager` access;
  plugin classloaders do not make implicit JDBC discovery reliable.
- Keep schema tables, required columns/nullability, primary/foreign keys,
  constraints, indexes, Kotlin queries, and Rust readers aligned. Older,
  malformed, or partially compatible schemas fail closed through the owning
  reset/rebuild boundary.
- `schema_version.generation` is the source-index change token. Every committed
  transition that can change source candidates, declarations, references,
  semantic graph, manifests, stage progress, pending application state, or
  overlay visibility must advance it in the same transaction.
- Workspace publication is a typed transaction:
  `OpenWorkspaceGeneration -> PreparedWorkspaceGeneration -> commit`.
  Candidates are owner-bound, rollback/discard is explicit, and publication
  becomes visible only after source, reference, graph/blocker, schema,
  compatibility, and identity evidence agree.
- Keep `module_index_progress`, file-stage rows, failures, and unapplied
  pending updates truthful. Readable rows do not prove completeness while
  initialized work is absent/incomplete, counts differ, stages remain pending,
  or a graph blocker is retained.
- Gradle ownership exists only as non-null association rows keyed by
  workspace-relative build root plus project path, with source-set name for
  source-set evidence. A file may have multiple owners. Never promote legacy
  `file_metadata.module_path` or `source_set` labels into proven identity.
- Package provenance is closed:
  `ProvenRoot`, `ProvenNamed(CanonicalName)`, or
  `Unproven(reason)`. Failed/absent parsing is never the root package, and no
  PSI type crosses the module boundary.
- Exact indexed relationship reads require FQ name, canonical declaration
  path, and non-negative declaration offset. FQ-only, null, or mixed anchors
  cannot satisfy exactness.
- The declaration row key cannot prove uniqueness of same-FQ overloads within
  one file. Do not use declaration-row count as callable-overload proof.
- Page results and their generation must be observed under the same store
  authority. A consumer must be able to reject a page after generation drift.
- Repository snapshots are immutable evidence keyed by Git tree, build
  classpath fingerprint, schema compatibility, and producer version. Publish
  complete data and manifest atomically; never move `latest-good` to an
  incomplete candidate.
- A worktree overlay may attach only a validated immutable repository snapshot
  with the exact current schema. It owns explicit tombstones and writable
  workspace deltas; attached base tables and interning aliases stay read-only.
  Missing, malformed, mismatched, symlinked, or ambiguous snapshot evidence is
  a typed rejection, never workspace-only fallback presented as attached.
- Snapshot retention and garbage collection must preserve current publication,
  latest-good, active overlays, and explicit pins before deleting unowned
  content.

## Change routing

- Add domain values and store-facing DTOs under `api` before adding raw
  primitives to the façade.
- Keep SQL with the smallest owner under `store/sqlite`. The façade delegates;
  it must not become a second implementation of each store.
- Snapshot and overlay changes usually cross `snapshot`, `sqlite/lifecycle`,
  `sqlite/overlay`, and indexer publication. Verify all four boundaries.
- A schema change always includes the version source, generated Kotlin/Rust
  alignment, schema validation, reset/migration behavior, and affected readers.

## Verification ladder

1. Run the focused class, for example:
   `./gradlew :index-store:test --tests '<fully.qualified.TestClass>'`.
2. Run `./gradlew :index-store:test`.
3. Schema changes also require the build-logic generator test and Rust
   `source_index_schema_version_smoke` alignment test.
4. Snapshot, overlay, generation, page, reference, stage, or completeness
   changes require the matching `:indexer:test` class that exercises the
   production host.
5. Run `./gradlew test` for a cross-module storage contract change.
