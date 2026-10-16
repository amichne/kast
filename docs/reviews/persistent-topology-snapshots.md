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
Its original receipt reports 42 persisted rows examined, 48 full-basis rows retained, and 60 visits to facts in that full basis.
The later review repair records 70 actual visits, including the omitted stage-admission scan.
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

## Artifact review repairs

Review examined candidate `1417a9ee30a0a9b9d1f385dd2c00c94f0f6c134a`.
The repairs preserve the incomplete offline-artifact scope. They do not implement native capture.

Malformed UTF-16 admitted two distinct producer names with one identity.
The Kotlin fixture gave `x\uD800` and `x\uD801` the same digest:
`86cfb9fc84d184b7df42574f9eeac83fd8812526b9b8fa35935509da85966e50`.
The first complete selector ran six cases. Five rejection cases failed, and the valid-Unicode control passed.
The log is `/tmp/kast-historical-unicode-red.log`.
An earlier test compilation failure selected no cases and does not count as product RED.

`HistoricalCanonicalFields` now admits Unicode scalar values before UTF-8 conversion.
Historical text parsers reject unpaired surrogates. Snapshot and query admission also reject malformed compiler identities from existing live types.
Canonical encoding returns finite rejection for malformed raw fields before it changes the row digest.
Valid pairs, supplementary characters, canonical framing, and UTF-16 source coordinates remain unchanged.
The existing independently computed snapshot digest still passes.

SQLite TEXT also needs byte admission before JDBC replacement decoding.
A corruption case stored the bytes `ED A0 80` with SQL `CAST`, then proved that JDBC returned a replacement character.
The reader accepted that corruption before the repair. `HistoricalSqliteText` now rejects malformed UTF-8 with `CONTENT_REJECTED`.
The helper preserves SQL NULL as a separate case and keeps valid text without normalization.

A second regression opened an artifact with 1,024 declarations and more than 8 MiB of data.
An external connection changed its first signature without replacing the inode.
The original reader returned the changed declaration under its admitted identity.
The log is `/tmp/kast-historical-reader-red.log`.

Each reader now owns a private copy. Admission and subsequent queries consume that copy.
The copy uses a 64 KiB buffer and enforces the existing 1 GiB limit during transfer.
Failed admission and normal close remove the owned file.
`copiedBytesRead` and `copiedBytesWritten` each report the actual artifact size for a successful open.
These counts exclude SQLite engine reads, metadata operations, and operating-system cache behavior.

Two batch-capacity cases exposed commits before a later selected file exceeded its row limit.
The fresh stage kept four declarations and two progress rows after rejection.
The partial stage gained a declaration and a progress row after rejection.
The log is `/tmp/kast-historical-unicode-green-storage-red.log`.

The coordinator now admits every selected file budget before the first selected-file commit.
It keeps bounded descriptors and materializes one admitted file batch at a time.
Both rejection cases preserve prior facts and progress, then succeed with an adequate budget.
The successful two-file retry examines 55 facts. The three-file retry examines 77 facts.
Each count includes stage admission, preflight, and materialization. The maximum batch contains five rows.
A focused metric regression exposed omitted stage-admission visits in three cases.
The cases reported 60, 44, and 66 instead of the independent totals of 70, 55, and 77.
The log is `/tmp/kast-historical-stage-metrics-red.log`.

The focused GREEN command passed 26 cases with zero failures, errors, or skips:

```sh
mise exec -- ./gradlew :topology:contract:spotlessApply \
  :evidence:topology-sqlite:spotlessApply \
  :topology:contract:test --tests '*HistoricalUnicodeContractTest' \
  --tests '*HistoricalSnapshotContractTest' \
  :evidence:topology-sqlite:test --tests '*HistoricalSqliteBatchCapacityTest' \
  --tests '*HistoricalSqliteUnicodeTest' --tests '*HistoricalSqliteReaderIsolationTest' \
  --tests '*HistoricalOfflineAdmissionTest' --console=plain
```

The counts are 15 contract cases and 11 SQLite cases.
The log is `/tmp/kast-historical-review-green.log`.

## Focused native follow-up

The current PR remains useful as complete offline-artifact groundwork with explicit native gaps.
D1 supplies independent contracts. D4 also contains necessary consumer integrity repairs, so it is not an optional optimization segment.
No new split is necessary for the current artifact scope.

A native owner must bind compiler resolution and recorded fingerprints to the same immutable inputs for the entire capture.
Those inputs include source text, generated inputs, imported membership, ordered resolution roots, compiler settings, and cache provenance.
`LiveSemanticReadAuthority` carries an IDEA owner and epoch. Its continuity contract explicitly excludes proof of external-input stability.
`SemanticNativeFileMemo` keeps hashes of mutable VFS inputs. Equal hashes before and after capture cannot exclude an external change and reversion.

The pinned platform sources expose append-only VFS content records through `PersistentFS`.
Those records do not bind compiler reads. `PersistentFSImpl` can read the physical filesystem, and `ZipHandler` can replace archive handles.
The examined source version is `262.9437.185`. It does not qualify the separate host version `262.10315.125`.
A bounded feasibility experiment exists. A successful same-IDE implementation remains unproved.

The decisive fixture needs an actual imported project and an external JAR with versions A and B.
Identical source must select different internal overloads under A and B.
After admission, a controlled handshake changes A to B, resolves the occurrence, and restores A before the final comparison.
Success needs compiler evidence bound to A, or typed rejection before publication with prior bytes unchanged.
The fixture must trace actual compiler input reads or exact cache provenance under cold and warm caches.
An unexplained cache hit cannot prove input binding.

After that proof, extend the existing owners in this order:

1. Extend the admitted native execution owner with the proven input capability.
2. Extend `GradleBuildProjectIndex` with detached complete membership, including rootless projects and empty source sets.
3. Export bounded declaration and occurrence facts from the symbol and relation owners, composed by the existing hosted runtime.
4. Connect `HistoricalCaptureCoordinator` to an explicit production request and the existing SQLite publication owner.
5. Add terminal file batches and fresh-session progress admission before native extraction reuse.

The current coordinator and historical adapter lack production callers.
The retained extractor needs `PublishedWorkspace` and skips unresolved facts. It does not supply a safe shortcut.
SQLite must remain outside normal live semantic composition.

The first native artifact can reuse the current full-capture API and all offline admission, identity, coverage, and query proofs.
Native resume needs an input identity distinct from the final artifact digest, atomic terminal file batches, and complete final closure.
Its oracle must interrupt one file, resume under a fresh admitted session, and prove that committed files receive no native extraction calls.
Changed inputs must reject reuse. Interrupted and uninterrupted captures must produce identical facts, coverage, identity, and offline pages.

A provisional implementation estimate spans 15–25 production and test files after the native feasibility gate.
The estimate excludes unknown platform work and unresolved external request wiring. It is not a schedule.
No native fixture, installation, restart, private-workspace action, or live query ran for this assessment.

## Repair gates and remaining limits

The final repair suite passed 85 selected Kotlin cases with zero failures, errors, or skips.
The counts are 26 historical contract, 46 historical SQLite, 12 capture coordinator, and one retained-graph characterization case.
The capture coordinator still uses scripted native results.

The combined gate command was:

```sh
mise exec -- ./gradlew :topology:contract:spotlessApply \
  :evidence:topology-sqlite:spotlessApply \
  :topology:contract:test --tests '*Historical*' \
  :evidence:topology-sqlite:test --tests '*Historical*' \
  :topology:build:test --tests '*historical*' \
  :topology:service:test --tests '*RetainedGraphIdentityCharacterizationTest' \
  :topology:contract:detekt :evidence:topology-sqlite:detekt \
  :topology:contract:checkMainKotlinFileLength :topology:contract:checkTestKotlinFileLength \
  :evidence:topology-sqlite:checkMainKotlinFileLength \
  :evidence:topology-sqlite:checkTestKotlinFileLength \
  verifyJsonContracts verifyKastArchitecture knowledgeImpact verifyKnowledgeBase --console=plain
```

The contract, capture coordinator, graph, and JSON tasks passed within the combined gate runs.
Two Detekt findings then needed small helper extractions in the SQLite code. They were style failures, not behavioral RED.
After the final extraction, the SQLite and remaining repository command passed:

```sh
mise exec -- ./gradlew :evidence:topology-sqlite:spotlessApply \
  :evidence:topology-sqlite:test --tests '*Historical*' \
  :evidence:topology-sqlite:detekt :evidence:topology-sqlite:checkMainKotlinFileLength \
  :evidence:topology-sqlite:checkTestKotlinFileLength \
  verifyKastArchitecture knowledgeImpact verifyKnowledgeBase --console=plain
```

The final command reported nine seconds. Its log is `/tmp/kast-historical-review-final-gates.log`.
JSON admission examined 631 fingerprints with zero violations.
The knowledge suite passed seven tests. Its citation scan examined 28 concepts and five reserved files with zero issues.
The repair impact scan found no affected concept.

The pinned Vale 3.24.0 procedure assessed this report against `docs/GLOSSARY.md`.
The result is `ASSESSED` with a grade advisory. It is not a clean report or proof of comprehension.
The report is `build/reports/persistent-topology/review-text-assessment.json`.

Logical-row retention remains bounded. The artifact limit and decoder scratch size do not establish a heap bound for large TEXT fields.
Process-kill cleanup, injected deletion failures, native capture, and native extraction reuse remain outside this repair's evidence.
The PR records the final pushed revision and its pre-push gate result.


## Late storage failures after committed progress

The artifact repair integrates `main` at `db57619760949a324a809a5fb26ba3469c083fa4` once.
The prior artifact head is `75d5c9e4e66366e37edc8ad7c60160782511e9d5`.
The final PR head identifies the integrated repair. The four original segment commits remain separate.

The independent oracle selects several files and throws `IOException` on the second `beforeCommit` call.
Real SQLite rows establish that the first selected file committed before the fault.
Both fresh and partial stages returned a bare storage rejection without their new committed-work receipt.
The two cases failed only at that receipt-loss assertion. Their recovery, publication preservation, and final fact assertions passed before the assertion.

```sh
mise exec -- ./gradlew :evidence:topology-sqlite:test \
  --tests '*HistoricalSqliteLateFailureTest' --console=plain
```

RED selected two cases and failed both at the missing receipt.
The log is `/tmp/kast-historical-late-failure-red.log`.
GREEN selected the same two cases and passed both after the repair.
The log is `/tmp/kast-historical-late-failure-green.log`.

`HistoricalStageRun` now owns acknowledged progress, transaction settlement, connection cleanup, and the final outcome.
`HistoricalStageFailure.Effect` preserves the finite fault, its phase, progress evidence, and both cleanup outcomes.
`HistoricalStageProgress.Settled` carries exact settled progress.
`HistoricalStageProgress.Uncertain` carries only the last acknowledged prefix and names commit or rollback uncertainty.
A failed commit stays uncertain even after a successful rollback. The next explicit call admits actual rows against its fresh basis.
The receipt counts rollback only for an uncommitted batch after successful rollback.
Cleanup failures preserve acknowledged commits and explicit stop causes.
`HistoricalStageCancellationException` remains a cancellation exception and carries the original cause, progress evidence, and cleanup outcomes.
The adapter catches expected I/O, SQL, and access failures. Unexpected defects and fatal JVM errors propagate.

The expanded cases cover fresh and partial stages with I/O and SQL faults, plus zero-progress failure.
Seven effect cases use real JDBC through a bounded connection seam.
They cover commit failures before and after the actual commit, rollback failure, close failure, and cancellation with successful or failed cleanup.
Each effect script rejects excess calls and asserts complete consumption.
The cancellation originates at `beforeCommit`. No JDBC-internal cancellation or process-crash durability claim follows from these cases.

A fresh late-fault case acknowledges one file and four rows, then rolls back one five-row batch.
A partial late-fault case reuses one file and four rows, acknowledges one file and five rows, then rolls back one three-row batch.
Their maximum materialized batch contains five rows. Their basis-fact counts are 70 and 60.
The commit-uncertainty cases report one acknowledged file and four rows in both outcomes.
The database contains one or two completed files, respectively, when the fault occurs before or after the second actual commit.
Fresh admission reuses the actual prefix. The retry reaches the same snapshot identity, facts, and coverage as uninterrupted publication.
Previous publication bytes stay unchanged before explicit publication.

Native capture, native extraction reuse, process-kill cleanup, and global installation remain unrun.
This repair changes the historical SQLite adapter and its focused evidence. It adds no live fallback or automatic capture.


### Integrated repair gates

The expanded storage selector passed 21 tests with zero failures, errors, or skips:

```sh
mise exec -- ./gradlew :evidence:topology-sqlite:spotlessApply \
  :evidence:topology-sqlite:test \
  --tests '*HistoricalSqliteLateFailureTest' --tests '*HistoricalSqliteStageEffectsTest' \
  --tests '*HistoricalSqliteStagingTest' --tests '*HistoricalSqliteBatchCapacityTest' --console=plain
```

Its log is `/tmp/kast-historical-late-failure-expanded-green.log`.
The final historical selectors passed 97 tests: 26 contract, 58 SQLite, 12 capture coordinator, and one legacy graph characterization.
All selected cases reported zero failures, errors, and skips.

```sh
mise exec -- ./gradlew :topology:contract:test --tests '*Historical*' \
  :evidence:topology-sqlite:test --tests '*Historical*' \
  :topology:build:test --tests '*historical*' \
  :topology:service:test --tests '*RetainedGraphIdentityCharacterizationTest' \
  :evidence:topology-sqlite:detekt :evidence:topology-sqlite:spotlessCheck \
  :evidence:topology-sqlite:checkMainKotlinFileLength \
  :evidence:topology-sqlite:checkTestKotlinFileLength \
  verifyJsonContracts verifyKastArchitecture knowledgeImpact verifyKnowledgeBase --console=plain
```

The command passed in 16 seconds. Its log is `/tmp/kast-historical-late-failure-final-gates.log`.
JSON admission examined 631 fingerprints with zero violations.
The knowledge suite passed seven tests. Its citation scan examined 28 concepts and five reserved files with zero issues.
The combined impact scan included the pending merge and named nine concepts.
The separate impact scan of the eight repair files named zero concepts.
Its report is `build/reports/persistent-topology/late-failure-knowledge-impact.json`.
An earlier gate failed only on unformatted lines in the new test files. The final formatter and Detekt gates passed.

Vale 3.24.0 assessed this report with the repository glossary. Its `ASSESSED` result includes a grade advisory.
The report remains `build/reports/persistent-topology/review-text-assessment.json`.
The PR records the final SHA and the pre-push result after the integration commit.

## Native input consistency gate, October 15, 2026

An explicit project capture followed by an offline source-usage answer remains incomplete.
The current investigation used `3e843eb6eb7c45ff402ba4b1103821afee059408` from `origin/main`.
The production adapter still rejects `EXTERNAL_INPUT_STABILITY_UNPROVEN`.
No production capture behavior changed.

Two platform mechanisms now have executable characterization cases in the existing headless native lane.
The lane uses IntelliJ Platform `262.9437.185` and its Kotlin plugin.
It owns its configuration and system directories and does not open the user's IDE.

- [NativeHistoricalArchiveInputTest](../../runtime/hosted/src/nativeTest/kotlin/io/github/amichne/kast/runtime/hosted/NativeHistoricalArchiveInputTest.kt) reads A, B, and A through the actual `ZipHandler` inside one read action.
  Atomic file replacement supplies each version without a VFS refresh or an IDE write.
  The starting and final archive bytes and SHA-256 digests match exactly.
  The unchanged-archive control returns A on every read.
- [NativeHistoricalBinaryCacheTest](../../runtime/hosted/src/nativeTest/kotlin/io/github/amichne/kast/runtime/hosted/NativeHistoricalBinaryCacheTest.kt) calls the actual `KotlinBinaryClassCache` service with valid Java class bytes.
  Equal file identity and modification stamp return the original cached result despite different supplied bytes.
  A fresh file identity with equal path and stamp consumes the replacement bytes.

These cases show that endpoint hashes and supplied file bytes do not prove input consistency.
They do not show that a source occurrence resolved against the wrong dependency.
They do not prove that every compiler read bypasses VFS content storage.
Complete capture still needs proof for both compiler reads and prior cached facts.

The four native cases **passed**, with zero failures, errors, or skips:

```sh
mise exec -- ./gradlew :runtime:hosted:nativeFixtureTest \
  --tests '*NativeHistoricalArchiveInputTest' \
  --tests '*NativeHistoricalBinaryCacheTest' --console=plain
```

The task also passed `detektNativeTest`.
Reports reside in `runtime/hosted/build/test-results/nativeFixtureTest`.
An initial JVM fixture failed because the archive API needed an IntelliJ Application.
The native fixture then needed a JUnit import correction and a named constant for Detekt.
Those fixture failures did not establish a product defect.

The historical baseline **passed** with 96 selected cases: 26 contract, 12 coordinator, and 58 SQLite cases.
The contract task used cached results. The coordinator and SQLite tasks ran in this checkout.
The legacy graph characterization was **not run** in this investigation.

```sh
mise exec -- ./gradlew :topology:contract:test --tests '*Historical*' \
  :topology:build:test --tests '*historical*' \
  :evidence:topology-sqlite:test --tests '*Historical*' --console=plain
```

Formatting, file-length, JSON, architecture, and knowledge guards **passed**:

```sh
mise exec -- ./gradlew :runtime:hosted:spotlessCheck \
  :runtime:hosted:checkTestKotlinFileLength verifyJsonContracts \
  verifyKastArchitecture knowledgeImpact verifyKnowledgeBase --console=plain
```

The impact report named zero affected concepts across the three changed files.
The prose report path is `build/reports/persistent-topology/native-input-text-assessment.json`.

### Deferred compiler capture

The consumer selects an imported module domain and receives one revision-pinned SQLite artifact.
The artifact must preserve empty source sets, rootless projects, exact declarations, occurrence ownership, and coverage.
An offline usage request must distinguish two overloads and exclude references from an unselected source module.
The acceptance fixture must answer from a copied artifact after removal of its original source directory.
An external JAR change during capture must preserve the admitted input version or reject before publication.
A rejection must preserve the previous artifact bytes.

The existing-IDE route still lacks an admitted capability that binds compiler reads and cached facts to fixed inputs.
An alternative is an explicit isolated capture with a fresh compiler session over frozen inputs.
That alternative changes compiler ownership and exceeds the immediate scope of this change.
Its input domain must include source, generated files, libraries, SDK files, compiler settings, and ordered resolution roots.
Copying files or changing permissions alone does not prove that the compiler uses immutable inputs.
Native capture, imported-project acceptance, and extraction resumption remain **not run**.

### Immediate module-impact report

The opt-in [topology impact command](../topology-impact.md) answers which Kast modules depend on a selected module.
It reuses the architecture convention's existing declared production dependencies.
The report includes a complete copy of that graph and a shortest dependency path for each consumer.
Its content digest identifies the graph without a source-revision claim.

The task does not enter historical SQLite publication or the hosted runtime.
It does not grant compiler authority or infer source usage from a declared dependency.
The explicit `kastTopologyEnabled=false` switch replaces the report with `DISABLED`.
Ordinary tasks do not schedule the report.

The command acceptance case first failed because the task did not exist.
After implementation, 23 focused cases **passed**, with zero failures, errors, or skips:

```sh
mise exec -- ./gradlew :build-logic:test \
  --tests '*ModuleImpactTest' --tests '*TopologyImpactTaskTest' \
  --tests '*GradleToolingArchitectureCoverageTest' \
  --tests '*ModuleKnowledgeProjectionTest' --console=plain
```

Eleven cases cover graph admission and paths. Five cover the real Gradle collector and command.
Seven existing cases protect class ownership and knowledge projection.
The command cases cover test-only exclusion, configuration-cache reuse, unknown-module rejection, disable behavior, and an independent digest assertion.
An ordinary `help` invocation does not schedule the report.

The documented repository command also **passed**:

```sh
mise exec -- ./gradlew topologyImpact \
  -PkastTopologyModules=:topology:contract,:evidence:topology-sqlite --console=plain
```

It reported 43 modules, 183 declared edges, and four consumers for the two selected modules.
The consumers were `:runtime:hosted`, `:topology:build`, `:topology:intellij`, and `:topology:service`.
The graph digest was `sha256:40c5a74329313e3ec30edde7b44eb03d77e622840752ebbdd007d617f8f0a91a`.
The report resides at `build/reports/topology/topology-impact.json`.

The JSON, architecture, knowledge, runtime-formatting, and runtime file-length guards **passed** again after the command change.
Their full log resides at `build/reports/topology/validation.log`.
Knowledge impact named four existing pages. Their claims about architecture admission, installed knowledge, and test scope remain unchanged.
No generated OpenWiki page needed an edit.

The pinned Vale procedure returned **ASSESSED** with advisories, not a clean result.
The report resides at `build/reports/topology/prose-assessment.json`.
Grade advisories remain because the documents preserve precise technical terms and evidence limits.
Other diction and sentence-length findings concern unchanged text in `docs/development.md`.
The supplied glossary excludes these documents from its scope. Reader comprehension remains **NOT_ASSESSED**.
