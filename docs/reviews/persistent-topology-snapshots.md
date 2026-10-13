# Persistent topology snapshot evidence

The offline implementation preserves revision identity, exact declarations, occurrence witnesses, and source-domain coverage.
Native capture remains incomplete. The current producer cannot prove uninterrupted stability of external resolution inputs or complete imported membership.
The explicit IntelliJ entry rejects publication with `EXTERNAL_INPUT_STABILITY_UNPROVEN`.
This is an implementation gap, not an absent IDEA installation.

## Revisions and scope

- Baseline: `54fc6390e55f219dc826a9465a01c514e3201d12` from refreshed `origin/main`.
- Branch: `feat/persistent-topology-snapshots`.
- D1 commit: `450d32c5c34a183ba4ca30f2cd805bff26f0678a`.
- D2 commit: `0ed32da3d3edab34caad7a0678d903d796a651fc`.
- D3 commit: `af915d76de4c1aa7f367fb65585d38042e9aa13e`.
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
The page union matches the independent witness table. QUALIFIED COVERAGE remains qualified after the last page.

The fresh-process case passed with a copied read-only artifact and the original case-owned source directory removed.
Its process environment has an empty executable path. Its classpath excludes the IntelliJ, hosted runtime, and App Server adapters.
This evidence proves the tested composition. It is not an operating-system prohibition on every possible filesystem read.

A separate generated-input case preserves its content hash, producer, unavailable owner, `CODE` context, and finite coverage causes through SQLite and JSON.
Its explicit source range selects `api`. The result contains no invented declaration owner.

The cursor suite first exposed three invalid-position acceptances across four selected tests.
After correction, all four cases passed. Nonexistent or wrong-query positions now return `CURSOR_MISMATCH`.
A valid continuation adds one decoded row for exact position admission.
The four-page oracle reports page work of 4, 5, 5, and 4 decoded rows, plus 2 request-selection rows per command.
Every command also reports its initial artifact admission work. These counters exclude SQLite engine page reads and in-memory metadata scans.

The final D3 command passed 22 tests with zero failures, errors, or skips in 3 seconds:

```sh
mise exec -- ./gradlew :evidence:topology-sqlite:test \
  --tests '*SqliteHistoricalSnapshotStoreTest' --tests '*HistoricalSqliteCursorTest' \
  --tests '*HistoricalOffline*Test' --tests '*HistoricalGeneratedEvidenceTest' \
  :evidence:topology-sqlite:detekt :evidence:topology-sqlite:checkMainKotlinFileLength \
  :evidence:topology-sqlite:checkTestKotlinFileLength --console=plain
```

Counts were 9 storage, 4 cursor, 5 CLI, 3 request-admission, and 1 generated-input case.
Detekt and both file-length guards passed. The log is `/tmp/kast-historical-d3-final.log`.

Review exposed three further correctness gaps.
Each query now keeps its reader's admitted inventory, so caller-created metadata cannot upgrade artifact coverage.
The regression first accepted forged COMPLETE COVERAGE. It now rejects with `QUERY_INVENTORY_MISMATCH`.

SQLite accepted wide offsets and coerced BLOB values into identities that differed during exact selection.
The independent corruptions produced product RED before correction.
The historical schema now uses `STRICT` tables. Integer decoding also rejects offsets outside the declared coordinate range.
Three focused cases passed after correction. Legacy SQLite schemas remain unchanged.
The logs are `/tmp/kast-historical-d4-integrity-red.log` and `/tmp/kast-historical-d4-integrity-green.log`.

Every successful command now includes four bounded coverage summaries in its manifest.
The summaries count each status per family and preserve finite causes. Inventory pages supply individual module coverage.
The files and declarations cases first failed because their manifests omitted coverage. Both passed after correction.

## D4: durable storage progress and bounded reads

The stage belongs to `SqliteHistoricalSnapshotStaging` within the topology SQLite adapter.
Each file commits its declaration facts, occurrence facts, and progress row in one transaction.
An interrupted transaction rolls back those facts and its progress together.
The ordinary reader rejects a stage. Publication copies a ready stage into a private candidate before final admission and atomic replacement.

A resumed operation needs a fresh `HistoricalCaptureBasis`.
The adapter compares the complete context, metadata, source inventory, and committed facts with that basis.
Changed context or changed committed facts reject reuse.
The budget limits files per operation and rows per file. An oversized file rejects before its facts or progress change.

This stage reuses SQLite writes. It does not resume native extraction.
The fresh basis still contains the complete snapshot, and each receipt reports that retained input separately.
Native streaming extraction and durable reuse under fresh native authority remain unimplemented.

The first four stage cases passed with the two coverage cases:

```sh
mise exec -- ./gradlew :evidence:topology-sqlite:test \
  --tests '*HistoricalSqliteStagingTest' --tests '*HistoricalOfflineCoverageTest' --console=plain
```

The command selected six tests and reported 4 seconds.
The log is `/tmp/kast-historical-d4-stage-first.log`.
The first staging command failed because its API did not exist. No behavioral assertion ran, so that failure is not product RED.

The independent read-retention case measured 14 materialized fact rows before the change.
Its limit of two rows failed while the fixture identity and counts remained correct.
The RED log is `/tmp/kast-historical-d4-stream-red.log`.
This measure counts logical facts, not bytes or heap use. Metadata has a separate count.

GREEN kept at most two fact rows, plus 34 metadata rows, for the same artifact identity and 48 admitted rows.
The adapter separately reports seven endpoint identity parses. SQLite engine page reads remain unmeasured.
A Unicode case preserves Kotlin ordering for supplementary and private-use characters, where SQLite text ordering differs.

The resumed fixture reused one file and four rows. It committed three files and ten rows, with a maximum batch of five rows.
Its receipt reports 42 persisted rows examined, 48 full-basis rows retained, and 60 visits to facts in that full basis.
Stage publication reports 48 stored rows and 148 examined rows across three passes. The artifact measured 90,112 bytes.
The strict structural fixture measured 73,728 bytes, compared with 69,632 bytes before `STRICT` tables.
These measurements do not establish reduced semantic work, total work, heap use, or latency.

The expanded suite passed seven stage cases and two streamed-admission cases.
Changed context, cancellation, and an injected I/O fault preserve committed progress and prior published bytes.
The final command passed 37 historical SQLite cases and 19 historical contract cases, with zero failures, errors, or skips.
Detekt and the selected file-length guards passed. The command reported 5 seconds.
The log is `/tmp/kast-historical-d4-final-gates.log`.

```sh
mise exec -- ./gradlew :evidence:topology-sqlite:test --tests '*Historical*' \
  :topology:contract:test --tests '*Historical*' \
  :evidence:topology-sqlite:detekt :topology:contract:detekt \
  :evidence:topology-sqlite:checkMainKotlinFileLength \
  :evidence:topology-sqlite:checkTestKotlinFileLength \
  :topology:contract:checkMainKotlinFileLength --console=plain
```

A final metric review found that the endpoint counter collapsed equal target and owner identities.
The self-reference case failed with one parse instead of two. The adapter now reuses each parsed identity and counts both parse operations.
The same selector then passed all three streamed-admission cases in 3 seconds, with zero failures, errors, or skips:

```sh
mise exec -- ./gradlew :evidence:topology-sqlite:test \
  --tests '*HistoricalSqliteStreamingTest' :evidence:topology-sqlite:detekt \
  :evidence:topology-sqlite:checkMainKotlinFileLength \
  :evidence:topology-sqlite:checkTestKotlinFileLength --console=plain
```

The logs are `/tmp/kast-historical-d4-self-metric-red.log` and `/tmp/kast-historical-d4-self-metric-green.log`.
The historical SQLite suite now contains 38 cases. The last complete historical run preceded this final added case.

## Repository gates

The JSON, architecture, and knowledge gates passed in 29 seconds:

```sh
mise exec -- ./gradlew verifyJsonContracts verifyKastArchitecture \
  knowledgeImpact verifyKnowledgeBase --console=plain
```

JSON admission examined 631 fingerprints with zero violations.
The knowledge suite passed seven tests. Its citation scan examined 28 concepts and five reserved files with zero issues.
The log is `/tmp/kast-historical-required-gates.log`.

The Gradle impact task covers current uncommitted changes.
A separate invocation supplied every changed path from the baseline through `--changed-file` to `.github/scripts/code_kb.py impact`.
Only the two glossary concepts that cite root `AGENTS.md` matched.
The root change adds a navigation link. Their cited policy remains unchanged, so no generated Claim needed a refresh.
The report is `build/reports/persistent-topology/knowledge-impact.json`.

The pinned Vale 3.24.0 procedure assessed the report and five changed navigation files against `docs/GLOSSARY.md`.
Its result is `ASSESSED`, with grade advisories and pre-existing long sentences in root `AGENTS.md`.
The result is not a clean report or proof of reader comprehension.
The report is `build/reports/persistent-topology/text-assessment.json`.

## Acceptance matrix

This matrix records evidence before publication. The draft PR records the later pre-push result and exact candidate revision.

| Segment or promised gate | Status | Evidence and limit |
|---|---|---|
| D1 reuse and historical contracts | passed | Existing characterization, independent identity oracle, finite coverage, and historical/live compiler rejection. |
| D2 Git attribution and standalone SQLite publication | passed | Real Git adapter and SQLite tests. Producer success uses scripted semantic facts. |
| D2 coherent native producer | unimplemented | Current input capture keeps hashes of mutable roots. No immutable resolution-input carrier exists. |
| D2 native fixture artifact and relocated fresh-process read | not run | The fresh-process read passed for a scripted artifact. The native producer cannot supply an artifact. |
| D3 exact offline report and explicit coverage | passed | Independent occurrence witnesses, paging, rejection, generated provenance, and fresh-process composition. |
| D3 first functional product acceptance | not run | The offline path passes with admitted fixtures. The real capture-to-report path lacks its native producer. |
| D4 per-file SQLite progress | passed | Seven cases cover interruption, reuse, changed context, cancellation, faults, capacity, tampering, and publication. |
| D4 bounded offline admission | passed | RED kept 14 fact rows. GREEN kept at most two. Full digest, counts, and Unicode order passed. |
| D4 durable native extraction and bounded capture | unimplemented | Every stage resume still consumes a complete fresh capture basis. |
| JSON, architecture, and knowledge gates | passed | Exact command and counts appear in the repository gate section. |
| Repository publication gate | not run | The pre-push hook must run `productBuildGate` on a clean commit. |

These statuses do not declare D2, D3, or D4 complete under the specification.
D1 can receive separate review at `450d32c5c34a183ba4ca30f2cd805bff26f0678a`, based on the recorded baseline.
D2 depends on D1. D3 depends on D2. D4 also carries corrections from the final cross-segment review.
The draft preserves those segment commits for review. No segment permits a native completeness claim from fixtures.

## Qualification limits

Native export, native reference projection, real imported-model completeness, and repository-scale capture are **not run**.
Native success also lacks the necessary producer implementation. Scripted success cannot satisfy D2 or D3 native acceptance.
No heap measurement, enterprise throughput result, or process-crash durability claim exists.
No live fallback, automatic indexing, global installation, IDE restart, merge, or release forms part of this work.

The native gap has specific owners:

- `GradleBuildProjectIndex` already traverses cached imported projects. Its detached projection must preserve projects and source sets without roots.
- `SemanticNativeModuleInputs` reads the current SDK and resolution roots. `SemanticNativeFileMemo` stores hashes, without immutable bytes or compiler input handles.
- `ProjectReadEpochVfsListener` excludes events outside the WORKSPACE ROOT. An IDEA read action does not prevent external file changes and reversions.
- A new native session must bind compiler resolution and fingerprints to the same immutable inputs before historical projection can succeed.
- Historical projection must keep current declaration and reference evidence. The retired extractor needs `PublishedWorkspace` and discards some unresolved facts.

The current native fixture supplies a model and uses the host SDK. It does not prove actual imported-model admission or immutable external inputs.
The necessary next fixture must exercise those mechanisms and an external change-and-revert case.
A native environment alone cannot resolve this implementation gap.
