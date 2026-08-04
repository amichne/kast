# Kast Indexer

This source map describes Kast's exact-root indexer. It is internal
documentation, not a second user guide.

Kast owns one semantic process for each admitted canonical workspace root. It
reuses an eligible process for that root or creates an isolated one. A
foreground IntelliJ IDEA or Android Studio process is not part of this flow.
On macOS, a supported installation supplies compatible runtime libraries.

```mermaid
flowchart LR
    demand["Public semantic demand"] --> admit["Admit exact-root indexer"]
    admit --> reuse{"Eligible process exists?"}
    reuse -- "yes" --> gradle["Use imported Gradle model"]
    reuse -- "no" --> launch["Create isolated indexer"]
    launch --> gradle
    gradle --> index["Reconcile graph and reference evidence"]
    index --> prepare["Prepare immutable generation"]
    prepare --> pointer["Replace current.json"]
    pointer --> ready["Admit exact manifest"]
    ready --> kotlin["Lease one READY generation"]
    ready --> rust["Resolve exact published read"]
    ready --> mutation["Acquire mutation permit"]
    mutation --> index
```

## Flow pages

- [Load and bootstrap](flows/load-and-bootstrap.md) explains exact-root
  admission, reuse, isolated startup, and descriptor ownership.
- [Gradle sync](flows/gradle-sync.md) explains import and model settlement.
- [Indexing and generation](flows/indexing-and-generation.md) explains source
  scope, resumable stages, and generation changes.
- [Graph queries](flows/graph-queries.md) explains refresh, read-only
  projections, coverage, and generation pinning.
- [Shutdown](flows/shutdown.md) explains lease, transport, worker, endpoint,
  and store close order.
- [Event-driven semantic atomicity](event-driven-semantic-atomicity.md)
  defines invalidation, workspace identity, two-phase immutable publication,
  `current.json` visibility, exact read leases, mutation permits, restart
  recovery, cache-only graph reads, and the Rust published-read boundary. Its
  [research ledger](research-ledger.md) maps each decision to source, platform
  contracts, or executable proof.

The [architecture decisions](architecture-decisions.md) record the boundaries
that keep these flows deterministic. The
[semantic evidence pressure-test gaps](semantic-evidence-pressure-test-gaps.md)
remain a separate current assessment.

## Stable invariants

1. One normalized root identifies the process, descriptor, socket, writer
   lease, index, and graph request.
2. Reuse requires matching root, release, process, endpoint, health, and
   capability evidence.
3. The imported Gradle model decides Kotlin source coverage.
4. Only the transition worker can write semantic evidence and publish it.
5. `current.json` is the default visibility boundary. The mutable writer
   database is not an external read surface.
6. READY carries one exact published manifest. Each read lease rejects revision
   or manifest movement.
7. A mutation permit withdraws READY and waits for active readers. The mutation
   returns only after a different manifest becomes READY.
8. A public graph request reads cached facts only. Missing facts request a
   worker transition; the public operation does not write them.
9. Restart recovery rebases the mutable writer from `current.json` before the
   store opens. It does not accept a partial live database.
10. Default Rust-local reads validate the published pointer and exact runtime
    manifest before and after each operation.
11. Process readiness, graph coverage, and reference coverage are separate.
    Foreground editor state cannot change indexer identity or evidence.
