# Published ij262-34 FIR source correspondence

The strict inventory gate passed for the published `ij262-34` FIR source scope.
All 4,486 published class entries match the unchanged bundled Kotlin plugin bytes.
All 1,809 published source entries match pinned upstream Git blobs under `compiler/fir`.
The complete visitor source also matches the inventory's preserved visitor.
Incident reproduction and replay remain NOT RUN. This result does not close the original incident.

## Outcome and scope

The consumer is `inventory.py audit --require-correspondence`.
Acceptance needs exact producer coordinates, artifact identity, complete class equality, complete source equality, and the existing inventory obligations.
The compiler distribution, native fixture inputs, and installed IDE remain unchanged.
The gate extends its existing distribution owner. It adds no compiler, semantic engine, or query behavior.

The proof covers the published FIR source archive and its corresponding bundled classes.
It does not identify the entire compiler's Git revision or prove non-FIR source correspondence.
The existing upstream revision remains `7342188118e8c24e12b5447f4430730ccc9c1989`, tagged `build-2.4.20-ij262-35`.
Its FIR source blobs equal the producer's exact `ij262-34` source archive.
No public `ij262-34` Git tag resolved. A matching tag name is unnecessary for this artifact-backed FIR correspondence.

## Independent evidence

JetBrains publishes the [binary archive](https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/org/jetbrains/kotlin/kotlin-compiler-fir-for-ide/2.4.20-ij262-34/kotlin-compiler-fir-for-ide-2.4.20-ij262-34.jar), [source archive](https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/org/jetbrains/kotlin/kotlin-compiler-fir-for-ide/2.4.20-ij262-34/kotlin-compiler-fir-for-ide-2.4.20-ij262-34-sources.jar), [POM](https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/org/jetbrains/kotlin/kotlin-compiler-fir-for-ide/2.4.20-ij262-34/kotlin-compiler-fir-for-ide-2.4.20-ij262-34.pom), and [SPDX record](https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/org/jetbrains/kotlin/kotlin-compiler-fir-for-ide/2.4.20-ij262-34/kotlin-compiler-fir-for-ide-2.4.20-ij262-34.spdx.json).
The retained POM and SPDX source coordinate both name `org.jetbrains.kotlin:kotlin-compiler-fir-for-ide:2.4.20-ij262-34`.
Their source reference declares that version. It does not establish a public Git commit for the entire compiler.

| Artifact or measurement | Expected | Observed | Status |
| --- | --- | --- | --- |
| Producer binary SHA-256 | `81db202e383c646067059af36683cdb32baf7a0202fa53ad7fccd7e58806f5f4` | Equal | PASS |
| Producer source SHA-256 | `b95b6a7851aa1b7d0d7f937d513ee1553d59d7f3db5f98fae6512bcaf1d7f990` | Equal | PASS |
| Producer SHA-1 sidecars | Published binary/source checksums | Equal downloaded bytes | PASS |
| Bundled plugin SHA-256 | `fb6becc3f5c709a01ee2cb32c834212935e94bbf5b01efb6745b7c0f62831fce` | Unchanged | PASS |
| Bundled compiler-common SHA-256 | `5b364960e81389e2576b3156c04f34d5ad0f14845236102940546f3e0794cb73` | Unchanged | PASS |
| Published classes | Every published class exists with identical bundled bytes | 4,486 matched, zero missing or different | PASS |
| Published source population | Exactly the independently mapped Git source set | 1,809 matched, zero missing, extra, duplicated, or different | PASS |
| FIR inventory | Every upstream element has one disposition | 164 elements, unchanged accounting | PASS |

[fir-source-map.tsv](upstream/provenance/fir-source-map.tsv) keeps every archive entry, Git path, Git blob identity, and SHA-256.
The independent extraction used `git ls-tree -r HEAD compiler/fir` in the preserved upstream checkout.
Each archive entry matched exactly one Git path and blob. No expected source record came from Kast.
The source map preserves every published Kotlin source entry rather than a selected visitor subset.

The comparison archives reside under `build/semantic-source-correspondence` as owned read-only inputs.
The source pin records their immutable hashes. Missing or changed inputs reject before correspondence admission.
The retained producer records and source map reside under `upstream/provenance`.
Original notices and the existing upstream Apache license remain intact.

## Gate changes and negative controls

The former strict gate trusted the declared `correspondence = "EXACT_SOURCE"` label.
A new independent control changed only that label and supplied no producer evidence.
The old gate returned PASS. The assertion expected FAIL and failed before implementation.
[label-control-before.txt](evidence/source-correspondence/label-control-before.txt) preserves that product RED.

The gate now needs a `PUBLISHED_FIR_SOURCES` witness from actual archive admission.
Admission checks producer coordinates, pinned bytes, exact populations, every class body, every source blob, and the preserved visitor.
The witness keeps the artifact hashes, upstream revision, source-map identity, and matched counts.
The consumer rejects a witness for a different pin.
The strict gate also needs a live adapter distribution. A declared label alone cannot satisfy it.

| Control | Expected result | Observed result | Status |
| --- | --- | --- | --- |
| Exact-source label without producer archives | Reject correspondence | `CORRESPONDENCE_UNPROVED` | PASS |
| Changed or absent class | Reject byte correspondence | `SOURCE_BINARY_MISMATCH` | PASS |
| Changed source or Git blob | Reject source correspondence | `SOURCE_CHANGED` | PASS |
| Missing/duplicate mapping or extra source | Reject exact population | `SOURCE_POPULATION_MISMATCH` | PASS |
| Duplicate archive entry | Reject ambiguous selection | `SOURCE_POPULATION_MISMATCH` | PASS |
| Different POM/SPDX version | Reject producer coordinate | `SOURCE_PROVENANCE_REJECTED` | PASS |
| XML entity in provenance | Reject unsafe metadata | `SOURCE_PROVENANCE_REJECTED` | PASS |
| Witness reused with another pin | Reject witness context | `SOURCE_PROVENANCE_REJECTED` | PASS |
| Partial source request | Reject before file reads | `INVALID_INPUT` | PASS |
| Changed readable source archive with unchanged pin | Reject artifact identity | `DISTRIBUTION_CHANGED` | PASS |

The unit controls exercise the real byte-admission rules with case-owned archives and independently declared inputs.
They do not supply compiler-semantic evidence.
The real strict replay independently compares the producer archives with the actual bundled plugin.
The changed-source control preserves a readable archive with 1,945 entries and valid UTF-8 source.
It changes one source comment and keeps the live compiler jars unchanged.

The tool uses locked `defusedxml==0.7.1` for producer POM parsing.
The project and standalone locks preserve that dependency. No lint or typing policy changed.

## Commands and outcomes

All audit commands ran from `config/kotlin-semantic-coverage/tooling`.
The initial replay used the original strict arguments and exited 1 with `CORRESPONDENCE_UNPROVED`.
[strict-before.json](evidence/source-correspondence/strict-before.json) preserves that actual result.
The successful replay added the exact producer archives:

```sh
uv run --locked --script inventory.py audit --require-correspondence \
  --plugin-jar ../../../relation/intellij/build/native-test/idea/plugins/Kotlin/lib/kotlin-plugin.jar \
  --compiler-common-jar ../../../relation/intellij/build/native-test/idea/plugins/Kotlin/lib/kotlinc.kotlin-compiler-common.jar \
  --fir-binary-jar ../../../build/semantic-source-correspondence/kotlin-compiler-fir-for-ide-2.4.20-ij262-34.jar \
  --fir-sources-jar ../../../build/semantic-source-correspondence/kotlin-compiler-fir-for-ide-2.4.20-ij262-34-sources.jar \
  --javap /Users/amichne/.local/share/mise/installs/java/oracle-graalvm-25.0.2/bin/javap \
  --required-cases ../inventory-evidence-cases.tsv \
  --junit ../../../runtime/hosted/build/test-results/semanticCoverageFixtureTest/TEST-io.github.amichne.kast.runtime.hosted.NativeScopedCacheManagerTraceTest.xml
```

Expected: exit 0, strict PASS, exact source witness, 164 classified elements, and no issues.
Observed: exit 0 with those results. [strict-after.json](evidence/source-correspondence/strict-after.json) preserves the witness.
The JUnit argument reuses the preceding R0 execution. This replay establishes new source correspondence, not another native semantic run.

The missing-source control omits both producer arguments. It exited 1 with `CORRESPONDENCE_UNPROVED`.
The changed-source control substitutes `changed-fir-sources.jar`. It exited 1 with `DISTRIBUTION_CHANGED`.
Both complete JSON reports remain in this evidence directory.

```sh
uv run --locked ruff format --check .
uv run --locked ruff check .
uv run --locked pyright
uv run --locked pytest -q
uv run --locked --script inventory.py --self-test
```

The locked procedures passed Ruff format, Ruff lint, strict Pyright, pytest 56/56, and the standalone self-test 56/56.
No assertion population was empty or skipped.

The root repository procedure is:

```sh
mise exec -- ./gradlew :check verifyJsonContracts verifyKastArchitecture knowledgeImpact verifyKnowledgeBase --console=plain --no-build-cache
```

The root procedure passed. Native fixture tasks remained up-to-date, and the exact 100-case execution gate passed with their unchanged reports.
Root inventory and resource gates also passed. The strict source replay remains a separate stronger result.
Source changes affect tooling and provenance only. No new production semantic code or native fixture input changed.

## Prose assessment

The local Vale 3.24.0 procedure returned ASSESSED with the repository glossary.
Its grade advisories remain for necessary compiler and provenance terms. No sentence-length, diction, or terminology finding remains in the changed documents.
The report does not establish reader comprehension.

## Remaining limits

The audited FIR correspondence gap is resolved.
The entire compiler's build commit and non-FIR source correspondence remain unproved.
The 163 unsupported inventory dispositions remain explicit gaps in broader semantic support.
The six archived upstream fixtures remain NOT RUN through Kast.
Incident reproduction, incident replay, and installed-host parity remain NOT RUN.
No compiler replacement or installed IDE change occurred. This report records local evidence before authorized pull request delivery.
