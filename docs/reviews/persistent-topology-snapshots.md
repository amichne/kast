# Persistent topology snapshot evidence

The offline implementation preserves revision identity, exact declarations, occurrence witnesses, and source-domain coverage.
Native capture remains incomplete. The current producer cannot prove uninterrupted stability of external resolution inputs or complete imported membership.
The explicit IntelliJ entry rejects publication with `EXTERNAL_INPUT_STABILITY_UNPROVEN`.
This is an implementation gap, not an absent IDEA installation.

## Revisions and scope

- Baseline: `54fc6390e55f219dc826a9465a01c514e3201d12` from refreshed `origin/main`.
- Branch: `feat/persistent-topology-snapshots`.
- Slopsentral source: `774716ce2cdee28133d463ae865f4c67bef68fb4`.
- Environment: macOS, Oracle GraalVM 25.0.2 through repository-owned `mise`.
- Profiles: `STRUCTURE_V1` and `REFERENCES_V1`.
- Coordinates: zero-based UTF-16 code units with an exclusive end.

The clean checkout refresh used these commands:

```sh
git fetch origin main
git switch --detach origin/main
git pull --ff-only origin main
git rev-parse HEAD origin/main
git switch -c feat/persistent-topology-snapshots
```

Both recorded revisions matched the baseline. Other worktrees remained unchanged.

## D1: owners and independent oracles

| Obligation | Current owner and decision | Smallest oracle |
|---|---|---|
| Complete file coverage | Reuse `CompleteTopologyGeneration` without changing its meaning. | Missing terminal files and foreign endpoints reject. |
| Live input coverage | Reuse `SemanticDependencyInventory` and `SemanticDependencySnapshot` rules. | Explicit empty membership passes. Missing module completion rejects. |
| Legacy SQLite durability | Preserve `SqliteTopologySnapshotStore`. | Existing rollback, corruption, restart, and location-collision cases pass. |
| Historical identity | Add contracts under `topology/contract/.../historical`. | Fixed canonical digest, signature sensitivity, exact locations, and immutable collections. |
| Live reference separation | Use a distinct `HistoricalDeclarationReference`. | The Java compiler accepts its historical return and rejects its return as `SymbolSelector`. |
| Graph identity | Exclude `GraphIndex` from the new report. | Two exact declarations become one legacy quotient node. This characterizes the existing limitation. |
| Revision attribution | Add the explicit Git adapter under `packaging/topology`. | Actual committed blob bytes match admitted hashes. Dirty and mismatched inputs reject. |
| Capture coherence | Add an explicit coordinator and a rejecting IntelliJ entry. | Scripted native rejection causes zero publication calls. Native success remains unavailable. |
| Offline report | Add artifact-only read contracts and bounded SQLite reads. | Exact source scope, foreign references, qualified emptiness, and cursor identity. |
| Durable progress | Extend the topology SQLite owner. | Same-context resume, changed-context refusal, and atomic facts/progress. |

The new publication path never converts a live epoch into `PublishedWorkspace`.
Snapshot admission proves content consistency. It does not authenticate a compiler run.
Imported dependencies remain separate from source occurrences.
The report does not use a target's dependency closure as its incoming source domain.

Initial characterization passed 32 selected tests:

```sh
mise exec -- ./gradlew :topology:contract:test \
  --tests '*TopologyGenerationContractTest' \
  --tests '*SemanticDependencyInventoryTest' \
  --tests '*SemanticDependencySnapshotTest' \
  :evidence:topology-sqlite:test --tests '*SqliteTopologySnapshotStoreTest' --console=plain
```

Counts were 7 generation, 9 inventory, 5 context, and 11 SQLite cases. The command reported 9 seconds.
A worker repeated the 14 semantic cases. That repetition adds no unique cases.

The historical contract suite preceded its implementation.
Its first command failed because the new API did not exist. No test assertion ran, so this is not product RED.
The first implementation passed five contract cases and one legacy graph characterization.

The expanded contract suite passed 17 cases. The coordinator passed 11 cases. The graph characterization passed one case.
The same command compiled the IntelliJ adapter and reported 38 seconds:

```sh
mise exec -- ./gradlew :topology:contract:test --tests '*Historical*' \
  :topology:build:test --tests '*HistoricalCaptureCoordinatorTest' \
  :topology:service:test --tests '*RetainedGraphIdentityCharacterizationTest' \
  :topology:intellij:compileKotlin --console=plain
```

The compiler case proves type incompatibility for the tested API use. It does not prove current authority or native execution.

The first D1 gate command passed 18 historical cases and one graph characterization in 5 seconds.
It also passed Detekt and both file-length guards:

```sh
mise exec -- ./gradlew :topology:contract:detekt \
  :topology:contract:checkMainKotlinFileLength :topology:contract:checkTestKotlinFileLength \
  :topology:contract:test --tests '*Historical*' \
  :topology:service:test --tests '*RetainedGraphIdentityCharacterizationTest' --console=plain
```

Pure query admission rejects unsupported scope, profile, limits, and foreign cursors before exact declaration lookups.
The command log is `/tmp/kast-historical-d1-final.log`.
Earlier task selection and style failures ran no behavioral assertion. They are not product RED.

Review found that historical admission accepted file-scoped `CODE` and `TYPE` occurrences.
The live contract permits only `IMPORT`, `ALIASED_IMPORT`, and `FILE_ANNOTATION` for that ownership.
The independent context matrix failed before the correction and passed after it:

```sh
mise exec -- ./gradlew :topology:contract:test \
  --tests '*HistoricalSnapshotContractTest.file scoped ownership*' --console=plain
```

RED selected one test and failed its ownership assertion in 3 seconds. GREEN passed the same test in 1 second.
The final historical suite passed 19 tests in 7 seconds. Detekt and both file-length guards also passed.
The logs are `/tmp/kast-historical-ownership-red.log`, `/tmp/kast-historical-ownership-green.log`, and `/tmp/kast-historical-d1-tests-final.log`.

## Qualification limits

Native export, native reference projection, real imported-model completeness, and repository-scale capture are **not run**.
Native success also lacks the necessary producer implementation. Scripted success cannot satisfy D2 or D3 native acceptance.
No heap measurement, enterprise throughput result, or process-crash durability claim exists.
No live fallback, automatic indexing, global installation, IDE restart, merge, or release forms part of this work.
