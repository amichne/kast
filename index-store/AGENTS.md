# Index store agent guide

`index-store` owns the SQLite-backed source index, workspace cache persistence,
and headless/indexer hydration APIs shared across kast runtimes.

## Ownership

Keep this unit focused on storage concerns and schema compatibility.

- Keep SQLite schema, migrations, interning codecs, and hydration helpers here.
  `SOURCE_INDEX_SCHEMA_VERSION`, table layouts, and query columns must stay
  aligned.
- Bootstrap `sqlite-jdbc` inside this module before `DriverManager` access. Do
  not rely on ambient JDBC registration from the host process because IntelliJ
  and other plugin classloaders may not register the driver for you.
- Keep this unit runtime-agnostic. Do not move IntelliJ PSI logic, CLI process
  management, or JSON-RPC transport code here.
- Treat schema resets, additive migrations, and cache hydration changes as
  compatibility-sensitive. Operational source-index reads belong in the Rust
  CLI; Kotlin should only read SQLite for headless hydration or targeted
  indexer/cache behavior.

## Verification

Prove storage changes here before relying on higher-level runtime tests.

- Run `./gradlew :index-store:test`.
- If you change schema bootstrap, connection setup, or hydration reads, exercise
  `SqliteSourceIndexStoreTest` and the affected headless/indexer tests.
