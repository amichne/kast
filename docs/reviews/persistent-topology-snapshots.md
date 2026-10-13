# Persistent topology snapshot evidence

The offline implementation preserves revision identity, exact declarations, occurrence witnesses, and source-domain coverage.
Native capture remains incomplete. The current producer cannot prove uninterrupted stability of external resolution inputs or complete imported membership.
The explicit IntelliJ entry rejects publication with `EXTERNAL_INPUT_STABILITY_UNPROVEN`.
This is an implementation gap, not an absent IDEA installation.

## Revisions and scope

- Baseline: `54fc6390e55f219dc826a9465a01c514e3201d12` from refreshed `origin/main`.
- Branch: `feat/persistent-topology-snapshots`.
- D1 commit: `450d32c5c34a183ba4ca30f2cd805bff26f0678a`.
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

## D2: publication and revision attribution

`SqliteHistoricalSnapshotStore` extends the existing topology persistence owner with a separate historical schema.
The artifact contains the manifest, modules, imported dependencies, files, declarations, occurrences, and coverage causes.
Publication writes a private candidate, examines its schema and logical content, and replaces the destination with an atomic move.
The destination remains unchanged after rejection or cancellation. A copied artifact needs no WAL sidecar.
The reader keeps its admitted connection, so later path replacement cannot substitute another snapshot.

The Git adapter compares only supplied paths with committed blob bytes and admitted content hashes.
It rejects dirty content, nested checkout roots, unsupported file modes, missing paths, and revision mismatches.
It does not discover semantic ownership or establish compiler authority.

```sh
python3 packaging/test-historical-git-attribution.py
```

The command passed 10 tests with zero skips in 2.922 seconds.
The nested-root case first failed because the adapter attributed duplicate child bytes to the parent repository.
Exact Git root admission corrected that defect. The same case then passed.
A separate fixture measured 6 Git calls, 1 comparison, and 90 bytes examined for a 45-byte source.

Two SQLite regressions changed stored ordinals without changing logical facts.
Both failed because the reader accepted the altered order. Both passed after contiguous and canonical order admission.
The logs are `/tmp/kast-historical-d2-ordinal-red.log` and `/tmp/kast-historical-d2-ordinal-green.log`.

The capture coordinator admits exact Git attribution before it calls the explicit native producer.
It compares the returned header, module domain, and complete file inventory before publication.
The production IntelliJ adapter rejects the missing stability proof. The coordinator success cases use scripted producer evidence.

The final D2 commands passed:

```sh
mise exec -- ./gradlew :evidence:topology-sqlite:test \
  --tests '*SqliteHistoricalSnapshotStoreTest' :evidence:topology-sqlite:detekt \
  :evidence:topology-sqlite:checkMainKotlinFileLength \
  :evidence:topology-sqlite:checkTestKotlinFileLength --console=plain
mise exec -- ./gradlew :topology:build:test --tests '*historical*' \
  :topology:build:detekt :topology:build:checkMainKotlinFileLength \
  :topology:build:checkTestKotlinFileLength :topology:intellij:compileKotlin \
  :topology:intellij:detekt :topology:intellij:checkMainKotlinFileLength --console=plain
```

The SQLite command selected 9 tests and reported 3 seconds.
The capture command selected 12 tests and reported 4 seconds. IntelliJ compilation passed, with no native fixture run.
The initial SQLite fixture used a macOS temporary-path alias. Its rejection preceded publication and was not product RED.
Tests now use the canonical case-owned path.

The structural fixture has 3 modules, 2 files, 2 declarations, and 12 coverage cells.
Its receipt reports 20 stored rows, 1 staged batch, 1 committed batch, and 40 decoded rows across 2 admission passes.
Its artifact size was 69,632 bytes. Replacement examines the prior artifact too, for 60 decoded rows across 3 passes.
SQLite engine page reads and heap use remain unavailable evidence.
The input artifact limit is 1 GiB. This limit does not establish acceptable heap use at that size.

## D3: offline source usage

The standalone reader accepts one artifact and an explicit operation: `inventory`, `files`, `declarations`, or `usage`.
Its Kotlin capability exposes no Git, source, compiler, workspace preparation, or mutation operation.
The offline composition does not enter the shipped live runtime.

```sh
mise exec -- ./gradlew :evidence:topology-sqlite:historicalRead \
  --args='inventory /absolute/path/snapshot.sqlite'
```

`STRUCTURE_V1` stores module, file, and declaration inventory. Its reference coverage is `NotCollected`.
`REFERENCES_V1` also stores exact internal occurrences with source ownership, classification, and source-domain coverage.
Both profiles describe an explicitly selected imported domain, not an inferred repository-wide domain.
The declaration families are `CLASSLIKE`, `CONSTRUCTOR`, `FUNCTION`, `PROPERTY`, and `TYPE_ALIAS`.
Generated inputs need explicit provenance. Unsupported or external semantic facts remain qualified or outside the profile.
Resolution inputs still belong to the producer's context obligation.

The request page size is 1 through 1000. Source and target domains each permit at most 128 entries.
The parser limits a request to 1,048,576 characters.
The `usage` operation needs JSON with `sourceModules`, exact historical `targets`, `size`, and an optional `cursor`.
The result keeps revision, snapshot, source scope, selected targets, occurrence coordinates, ownership, coverage, and continuation.
The process returns typed JSON for rejection. Consumers must examine `type` and `reason`, not the process exit code alone.

The explicit oracle contains six modules, four files, six declarations, and four reference occurrences.
It distinguishes overloads, repeated compiler identity at different locations, main and test ownership, empty membership, and rootless membership.
The four occurrence ranges select `Api` or `api` in the fixture source text.
The page union matches the independent witness table. Qualified coverage remains qualified after the last page.

The fresh-process case passed with a copied read-only artifact and the original case-owned source directory removed.
Its process environment has an empty executable path. Its classpath excludes the IntelliJ, hosted runtime, and App Server adapters.
This evidence proves the tested composition. It is not an operating-system prohibition on every possible filesystem read.

## Qualification limits

Native export, native reference projection, real imported-model completeness, and repository-scale capture are **not run**.
Native success also lacks the necessary producer implementation. Scripted success cannot satisfy D2 or D3 native acceptance.
No heap measurement, enterprise throughput result, or process-crash durability claim exists.
No live fallback, automatic indexing, global installation, IDE restart, merge, or release forms part of this work.
