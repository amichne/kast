<!-- AGENTS.md: generated navigation; keep this file checked in -->
<!-- generated: 2026-09-11 | hash: 1cb33de6ffce -->

# indexer

## Purpose

Hosts Kast's semantic runtime inside IntelliJ, owns the sidecar transport, and publishes bootstrap/cache readiness.

## Key Files

- [src/main/kotlin/io/github/amichne/kast/indexer/KastIndexerMain.kt](src/main/kotlin/io/github/amichne/kast/indexer/KastIndexerMain.kt) - executable entry.
- [src/main/kotlin/io/github/amichne/kast/indexer/KastIndexerHost.kt](src/main/kotlin/io/github/amichne/kast/indexer/KastIndexerHost.kt) - IntelliJ host integration.
- [src/main/kotlin/io/github/amichne/kast/indexer/InstalledIndexerTransport.kt](src/main/kotlin/io/github/amichne/kast/indexer/InstalledIndexerTransport.kt) - installed transport.
- [src/main/kotlin/io/github/amichne/kast/indexer/IndexerRequestPolicy.kt](src/main/kotlin/io/github/amichne/kast/indexer/IndexerRequestPolicy.kt) - request admission policy.
- [src/main/resources/META-INF/plugin.xml](src/main/resources/META-INF/plugin.xml) - IntelliJ plugin registration.
- [src/main/scripts/kast-indexer](src/main/scripts/kast-indexer) - launcher script.

## Subdirectories

- `src/main/kotlin` - bootstrap, transport, request policy, and application host.
- `src/main/resources` - IntelliJ plugin descriptor.
- `src/main/scripts` - installed launcher.
- `src/test` - transport, bootstrap, and policy evidence.

## Entry Points

- Gradle project: `:indexer`.

## Navigation Hints

- Start with the [repository knowledge](../knowledge/modules/runtime-hosts.md) and follow its `code_sources` for source evidence. Root engineering rules remain authoritative; this map adds navigation only.

- For process/bootstrap failures, start with the main, application starter, and bootstrap publisher.
- For wire stalls or request isolation, start with transport, frame codec, request peer, and their focused tests.
