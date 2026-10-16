# Kotlin semantic coverage inventory

The live inventory gate passes for 164 declared FIR identities. The separate strict gate passes with published `ij262-34` FIR source evidence.

The adapter uses the Kotlin plugin from `ideaIC:262.9437.185`. Its Analysis API and FIR classes reside in `kotlin-plugin.jar`. Its compiler resource reports `2.4.20-ij262-34`.

The separate bundled command-line compiler reports `2.3.20-release-208`. The Gradle Kotlin version is `2.4.21`. These identities describe different compiler consumers.

The upstream pin names `build-2.4.20-ij262-35` at `7342188118e8c24e12b5447f4430730ccc9c1989`.
JetBrains publishes the binary and source archives for `kotlin-compiler-fir-for-ide:2.4.20-ij262-34`.
All 4,486 published classes equal their unchanged bundled plugin entries.
All 1,809 published source files equal the pinned upstream Git blobs under `compiler/fir`.
The published visitor also equals the preserved visitor source.
This proves exact correspondence for the audited FIR source scope.

The producer POM, SPDX record, checksums, and complete source map reside in `upstream/provenance`.
The strict gate needs both peer archives and the actual bundled jars.
The gate rejects a declared `EXACT_SOURCE` label without admitted source evidence.
No public `ij262-34` Git tag resolved during this work.
The entire compiler's build commit and non-FIR source correspondence remain unproved.

The adapter enum declares `ZERO`, `AT_MOST_ONCE`, `EXACTLY_ONCE`, `AT_LEAST_ONCE`, `MORE_THAN_ONCE`, and `UNKNOWN`. The delivery scope names four standard-library invocation kinds. `ZERO` and `MORE_THAN_ONCE` remain separate, unproved adapter cases.

## Artifacts

- `pin.toml` records compiler identities, upstream identities, hashes, and the unresolved correspondence.
- `upstream/FirTree.kt` preserves the full upstream declaration inventory.
- `upstream/FirVisitor.kt` provides an independent upstream generator result for the element population.
- `upstream/commit.txt` preserves the Git commit object. Its Git object identity must equal the pin.
- `upstream/binary-fir-elements.txt` records the 164 visitor identities from the adapter binary.
- `inventory.tsv` keeps every extracted element, category, declaration property, and source line.
- `classifications.tsv` assigns exactly one disposition, reason, and evidence reference to each element.
- `upstream/resolver-source-index.tsv` records 1332 upstream resolver source identities and their Git blob identities.
- `selected-fixtures.tsv` pins six source fixtures and 18 upstream expected artifacts.
- `inventory-evidence-cases.tsv` records two independently assigned R0 case IDs. The recorded baseline supplies their actual result identities.

The selected upstream files preserve their bytes, directives, file and module markers, diagnostic markers, and expected output. Their hashes protect this preservation. The audit does not adapt or run these upstream fixtures.

`FirBlock` has the traversal-only disposition. The recorded native lazy fixture keeps the exact `InterestRatesClientImpl` constructor occurrence inside its callback block. This proof covers that scoped fixture only.

The other 163 entries remain unsupported because they lack item-specific executable evidence for the declared relation. An unsupported disposition records an unproved obligation. It cannot satisfy the required trace-completion gate.

Implemented and traversal-only dispositions need passing evidence for their declared case IDs. A manifest entry alone supplies no execution evidence. The execution gate rejects missing, duplicate, unexpected, skipped, failed, erroneous, and zero result populations.

## Commands

From `config/kotlin-semantic-coverage/tooling`, run the checks:

```sh
uv run --locked ruff format --check .
uv run --locked ruff check .
uv run --locked pyright
uv run --locked pytest
uv run --locked --script inventory.py --self-test
```

For the recorded R0 evidence, run the accounting gate:

```sh
uv run --locked --script inventory.py audit \
  --required-cases ../inventory-evidence-cases.tsv \
  --junit ../evidence/baseline/hosted/TEST-io.github.amichne.kast.runtime.hosted.NativeScopedCacheManagerTraceTest.xml
```

For the actual adapter inventory, add the live binary inputs:

```sh
uv run --locked --script inventory.py audit \
  --require-element-correspondence \
  --plugin-jar /path/to/extracted/kotlin-plugin.jar \
  --compiler-common-jar /path/to/extracted/kotlinc.kotlin-compiler-common.jar \
  --javap /path/to/javap \
  --required-cases ../inventory-evidence-cases.tsv \
  --junit ../evidence/baseline/hosted/TEST-io.github.amichne.kast.runtime.hosted.NativeScopedCacheManagerTraceTest.xml
```

The tool hashes the actual jars and reads their version resources. It runs `javap` against an owned snapshot of the admitted plugin bytes. The live visitor population must equal the upstream population. A replacement plugin fails even when the catalogue stays unchanged.

For the strict FIR source claim, add both published archives:

```sh
uv run --locked --script inventory.py audit \
  --require-correspondence \
  --plugin-jar /path/to/extracted/kotlin-plugin.jar \
  --compiler-common-jar /path/to/extracted/kotlinc.kotlin-compiler-common.jar \
  --fir-binary-jar /path/to/kotlin-compiler-fir-for-ide-2.4.20-ij262-34.jar \
  --fir-sources-jar /path/to/kotlin-compiler-fir-for-ide-2.4.20-ij262-34-sources.jar \
  --javap /path/to/javap \
  --required-cases ../inventory-evidence-cases.tsv \
  --junit /path/to/the/current/R0/junit/report.xml
```

Get the exact [binary archive](https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/org/jetbrains/kotlin/kotlin-compiler-fir-for-ide/2.4.20-ij262-34/kotlin-compiler-fir-for-ide-2.4.20-ij262-34.jar) and [source archive](https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/org/jetbrains/kotlin/kotlin-compiler-fir-for-ide/2.4.20-ij262-34/kotlin-compiler-fir-for-ide-2.4.20-ij262-34-sources.jar).
The admitted witness keeps their hashes, the upstream revision, source-map identity, and matched populations.
Missing archives, changed bytes, duplicate entries, source omissions, mismatched coordinates, and reused proof from another pin reject.
The archives serve as comparison inputs. They do not replace the compiler or alter the installed IDE.

For the complete executable manifest, run the execution gate with reports from the actual run:

```sh
uv run --locked --script inventory.py execution \
  --required-cases ../cases.tsv \
  --junit /path/to/the/selected/actual/junit/reports
```

The manifest needs `case_id` and `test_result_id` columns. A result identity has the form `classname#method`. Each case needs a distinct result identity. The parser keeps `PASS`, `FAIL`, `ERROR`, `SKIP`, and `NOT_RUN` separate. A suite-level error remains a separate setup or suite failure. A testcase error remains `ERROR` without an inferred cause.

## Frozen semantic resources

The separate input gate covers 53 immutable semantic resources. R1 supplies 25 resources. R2 supplies 26 resources. The reviewed R4 table supplies two resources.

The authored case manifests define the expected sources. The R1 identity table supplies hashes for its shared source inputs. The R2 manifest supplies its own source hashes. The separate R4 table preserves the two directly referenced control inputs.

```sh
uv run --locked --script inventory.py inputs
```

The gate rejects changed bytes, missing resources, duplicate identities, extra identities, extra source files, and mismatched hashes between independent manifests.
Assertion files remain outside the resource population. Changes to assertion files do not change these source identities.

For reviewed request, scope, and budget carriers, pass a separate table:

```sh
uv run --locked --script inventory.py inputs \
  --reviewed-carriers /path/to/reviewed-carriers.tsv
```

That table needs `artifact` and `sha256` columns. Each artifact names a file relative to the repository. A declared carrier must exist and match its reviewed hash. Duplicate or empty carrier tables fail.

A carrier hash protects the entire file. It does not isolate semantic values from assertions in the same file. The integrator owns the reviewed carrier selection.

The resource gate proves file identity and population accounting. It does not prove compiler success, fixture consumption, current workspace admission, or trace completion. The exact native case gate supplies separate behavior evidence.

## Evidence limits

The validator tests prove rejection of negative controls. A live control adds one archive entry while it keeps the original visitor bytecode. The changed archive fails with `DISTRIBUTION_CHANGED` under the unchanged catalogue. They do not provide new native semantic results. The copied baseline remains evidence of the recorded run only.

The audit keeps the full FIR population. It does not use relevance classifications to prune descendants. The selected upstream fixtures remain `NOT RUN` through Kast. The audited FIR source correspondence now passes. Other compiler source scopes remain unproved.

The tool reads owned inventory files, declared source files, adapter jars, and selected JUnit reports. It uses an owned temporary snapshot for the binary read. It does not change an installed IDE.

## Upstream provenance and license

The source comes from [JetBrains Kotlin at the pinned commit](https://github.com/JetBrains/kotlin/tree/7342188118e8c24e12b5447f4430730ccc9c1989). The [upstream generator guide](upstream/Readme.md) identifies `FirTree.kt` as its element inventory.

The extraction used a shallow Git checkout of `build-2.4.20-ij262-35`. `git ls-tree -r HEAD analysis/analysis-api/testData/components/resolver` supplied the independent resolver index. `git show HEAD:<path>` supplied every copied upstream artifact. `javap -classpath <adapter-plugin.jar> org.jetbrains.kotlin.fir.visitors.FirVisitor` supplied the independent binary element population.

The original source notices remain in the copied files. [Apache License 2.0](upstream/LICENSE.txt) governs the upstream source and fixtures. The upstream source names JetBrains and Kotlin contributors as copyright owners.
