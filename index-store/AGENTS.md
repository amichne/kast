# Index store agent guide

`index-store` owns the SQLite-backed source index, workspace cache persistence,
and headless/indexer hydration APIs shared across kast runtimes.

## Ownership

Keep this unit focused on storage concerns and schema continuity.

- Keep SQLite schema, migrations, interning codecs, and hydration helpers here.
  `SOURCE_INDEX_SCHEMA_VERSION`, table layouts, and query columns must stay
  aligned.
- Treat `schema_version.generation` as the source-index change token. Increment
  it in the same write transaction as candidate-bearing tables, module index
  progress, or pending-update applied state so read-only consumers can prove a
  stable snapshot without changing the schema.
- Keep `module_index_progress` and unapplied `pending_updates` truthful. A
  readable row set is not complete index evidence while the initialized
  progress set is empty/incomplete, indexed counts differ from totals, or
  updates remain pending.
- Bootstrap `sqlite-jdbc` inside this module before `DriverManager` access.
  IDEA and other plugin classloaders require explicit driver registration.
- Keep this unit runtime-agnostic. IDEA PSI logic, CLI process management, and
  JSON-RPC transport code live in their runtime, CLI, and server owners.
- Treat schema resets, additive migrations, and cache hydration changes as
  contract-sensitive. Operational source-index reads belong in the Rust CLI;
  Kotlin reads SQLite for headless hydration or targeted indexer/cache
  behavior.
- Return paged index evidence and its generation atomically under the same
  store lock. Every committed transition that can change indexed declarations,
  references, manifests, or reconciliation state must advance the generation;
  consumers use it to reject stale continuation pages.

## Verification

Prove storage changes here before relying on higher-level runtime tests.

- Run `./gradlew :index-store:test`.
- For page/generation changes, also run `./gradlew :backend-idea:test` to prove
  production continuation invalidation rather than only store-local behavior.
- For generation/progress/pending changes, prove rollback atomicity and
  before/after generation behavior in `SqliteSourceIndexStoreTest`.
- If you change schema bootstrap, connection setup, or hydration reads, exercise
  `SqliteSourceIndexStoreTest` and the affected headless/indexer tests.
