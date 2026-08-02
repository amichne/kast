# ADR 0033: Exact-root Kast indexer authority

Status: Accepted

Date: 2026-08-01

## Reason this record remains

The indexer uses IntelliJ and Kotlin libraries, but an editor process is not a
Kast authority. This boundary spans routing, packaging, setup, and lifecycle
code.

## Decision

Kast admits one healthy indexer for one canonical workspace root. The admitted
indexer is the only semantic server and the only persistent source-index
writer. Reads, mutations, graph refresh, reference indexing, readiness,
leases, and lifecycle operations use the same identity.

Normal demand starts at the public interface:

```text
cd <canonical-workspace-root>
kast up
```

Kast reuses an eligible healthy exact-root indexer. If none exists, it creates
an isolated indexer with its own configuration, system, log, descriptor,
socket, and VFS paths. On macOS, a supported IntelliJ IDEA or Android Studio
installation supplies compatible runtime libraries. Its foreground process is
not inspected or controlled.

The internal indexer payload stays inside the release and is never installed
into a foreground application. Releases contain no public IntelliJ extension,
update feed, signing task, verification task, archive, or publication job.

Runtime readiness, semantic graph coverage, and reference coverage remain
separate typed facts. `READY` does not imply complete persisted graph coverage.

## Source and proof

- `cli-rs/src/execution/runtime/backend/workspace_admission.rs`
- `indexer/`
- `analysis-server/src/main/kotlin/io/github/amichne/kast/server/`
- `index-store/src/main/kotlin/io/github/amichne/kast/indexstore/store/`
- `scripts/smoke-macos-indexer.sh`

Any new semantic process, editor lifecycle edge, endpoint owner, or source
index writer must replace this decision explicitly.
