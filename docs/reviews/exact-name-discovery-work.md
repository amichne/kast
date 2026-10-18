# Exact-name discovery work

Date: October 17, 2026. Source base: `877cf9a4d27a2202892dec31a92c53e333669b20`, with this task's local changes.

Exact-name discovery exhausted its work allowance before callers analysis started.
The change completes the same discovery question over the full Kast production-source dataset under the same allowance.
The installed candidate also completes root discovery and the original production callers question under the default grant.

## Original failure

The linked chat, **Create focused Kast demos**, recorded a repeated discovery failure.
Its request selected `SavedConfigurationDocument`, `CLASS`, directory `.`, and source set `main`.
It used the default execution budget and requested retained symbol evidence.

Read `798023fb-75de-4a15-87d2-7aff10cd0242` returned `COMPLETION_UNPROVEN` with `WORK_LIMIT_REACHED` and `DISCOVERY_INCOMPLETE`.
The hosted read lasted 5.225 seconds within its 15-second semantic allowance.
Its log recorded 1,648 admitted source files, 235 declaration scans, and 497,408 AST nodes.
Neither K2 analysis nor reference search started.
The log recorded no unexpected failure.

This rejection proves a work-policy stop.
The investigation did not sample CPU, heap, or system memory pressure.
Those resource measurements cannot explain away the recorded work-policy decision.

## Meaning of work

The public default grants 2,000,000 work units.
[`QueryExecutionState.indexedDiscoveryBudget`](../../query/service/src/main/kotlin/io/github/amichne/kast/query/service/QueryExecutionState.kt) reserves half the remaining work for exact refinement.
The recorded discovery page used its entire 999,999-unit child allowance.

A unit counts an explicit production operation, such as a partition transition, tree visit, or candidate projection.
It does not count a reference, CPU instruction, or byte of memory.
Native index internals remain opaque.
The change preserves both the public grant and the refinement reserve.

## Cause and earlier changes

The directory dispatcher selected the exhaustive partition producer before the exact-name index route.
The producer admitted the directory and source policy, then visited unrelated declaration trees before it applied the exact name.
Large unrelated bodies exhausted the allowance before the target file supplied a candidate.

Commit `aa0a9e976` reduced PSI creation through audited AST traversal.
It kept full tree coverage to preserve local declarations.
Commit `9449c653d` restricted supply to directory partitions and removed broad provider scans.
A root-directory request still selected all admitted production sources.
These changes reduced different work and kept the expensive tree visits.

The earlier package-scoped callers case completed.
That contrast showed a smaller search population, rather than a repaired root-directory algorithm.
An unchanged fresh rerun reproduced the root-directory rejection.

## Change and protected cases

[`IntellijDeclarationIndexes`](../../symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijDeclarationIndexes.kt) supplies conservative candidate files from the native word index.
[`IntellijExactNameCandidateFiles`](../../symbol/intellij/src/main/kotlin/io/github/amichne/kast/symbol/intellij/IntellijExactNameCandidateFiles.kt) produces immutable exclusion evidence only after the index completes.
The existing bounded VFS inventory remains authoritative for source supply.
It excludes ordinary noncandidate files before partition capacity and PSI acquisition.
Existing PSI admission and K2 refinement still decide declaration identity.

Unicode names, spaced backtick names, names longer than 100 characters, and `Companion` keep exhaustive discovery.
An implicit companion object has the name `Companion` without that identifier token in its source.
Oversized files remain in the original PSI path because the word index can omit them.
Their original finite rejection must survive.

Local classes, functions, properties, overloads, source scope, cancellation, and incomplete-result rejection remain protected.
Index and directory preparation share one work allowance and one retained-byte limit.
VFS counters still count excluded children.

## Full-source native evidence

The fixture copied all 1,760 production Kotlin files from the current checkout without source edits.
The snapshot contained 10,651,479 bytes.
Its SHA-256 was `ffb36807049f5eb53708f51b54ec490023e44215093f915b099c34befb7b2e01`.
The digest input used sorted relative paths, NUL separators, and exact source bytes.

Both routes received the same root-directory question, 999,999 work units, and 15 seconds.

| Measurement | Exhaustive route | Candidate route |
| --- | ---: | ---: |
| Outcome | `WORK_LIMIT_REACHED` | Complete |
| Work units | 999,999 | 26,874 |
| Declaration scans | 232 | 5 |
| AST nodes | 497,173 | 12,813 |
| VFS children examined | 2,364 | 2,364 |

The authored oracle selected the exact distribution-contract source file and declaration offset `411`.
K2 established its class kind, qualified identity, and exact source range with one analysis call.
All 13 native cases passed, including oversized files and implicit companion objects.
The 231 ordinary module cases passed in a serial run.
Two concurrent aggregate runs failed in Gradle result-file handling, without an asserted product failure.

```sh
mise exec -- ./gradlew :symbol:intellij:test --console=plain
mise exec -- ./gradlew :symbol:intellij:nativeFixtureTest
```

Native XML resides in `symbol/intellij/build/test-results/nativeFixtureTest/`.
The fixture uses IntelliJ build `262.9437.185` and GraalVM `25.0.2`.
It copies production sources into one native fixture module.
It does not reproduce the installed Gradle module graph.
Single ordered duration samples do not establish a stable latency improvement or a completed-query speedup percentage.
The change does not prove globally minimal work.

## Installed acceptance

The user authorized candidate installation and replay after local checks passed.
The normal Host installer needs a numeric version, so the candidate uses `0.20261018.25702`.
All 38 dependency jars match the approved archive byte for byte.
Only `META-INF/plugin.xml` and `kast-hosted-query.properties` differ within the runtime jar.
Those files carry the numeric version.
The installed plugin matches all 39 candidate files byte for byte.

The Host installer admitted the current Control contract before replacement.
IntelliJ stopped and restarted through its normal lifecycle.
Its startup log records the candidate version under IDEA `262.10968.63`.
Control remains `0.0.3620`, with its manifest unchanged.
The candidate archive SHA-256 is `d8367ed18db19e3cd7b3f6b0d44241b2695fb0a377e516a34b5611dc5709637f`.
A private inert archive preserves the prior plugin and receipt.

The exact original root-directory discovery request completed with one compiler-confirmed class and exhaustive coverage.
It used 27,086 discovery work units, five candidate files, and 12,813 AST nodes.
The default grant remained 2,000,000 work units and 15 seconds, without clamping.
Read `848a9756-3825-4412-a620-4b75e2294366` lasted 1,015.533 milliseconds.

The original package-scoped `parse` discovery also completed.
Its fresh issued reference then entered the unchanged production `main` callers request.
The native provider exhausted that domain with three compiler-confirmed occurrences, without failures or omissions.
Read `261934ed-08b4-4aa3-8681-340b19ffb39e` lasted 2,460.946 milliseconds.

The caller identities match the independently recorded prior answer:

- `io.github.amichne.kast.cli.installation.PriorRetirement.Companion.admit`
- `io.github.amichne.kast.distribution.cli.runtimeSocket`
- `io.github.amichne.kast.appserver.InstalledSavedConfigurationIngress.readPinned`

Both reads used the same fresh Host identity, `7d31f87b-b113-4317-b1b4-37858bf8ea9e`.
The first read used epoch `1`; parser discovery and callers used epoch `2`.
Each receipt records `COMPLETE`, with no unexpected failure.
These durations are individual native observations, not a stable latency benchmark.
Coverage excludes tests, generated sources, libraries, reflection, and external consumers.

Receipts reside in `build/reports/callers-work-limit/installed-acceptance/`.
They preserve the installation plan, active receipt, loaded-version log, three tool replies, native diagnostics, and prior plugin archive.
Passive Control status still reports `EPOCH_UNAVAILABLE` for its loaded-version projection.
The native startup log and actual semantic replies establish this installed acceptance separately.

## Import failure

The first installed Tool RPC run in this exact checkout stopped before semantic execution.
Preparation request `8d1eab59-ca7e-41aa-bee5-6ecddcdfdc71` returned `IMPORT_FAILED`.
The IDE reported `ExternalSystemPartialResolutionException` without the specific cause.
The Gradle daemon log identified missing trust for `groovy-4.0.33.module` during strict dependency verification.
Both recorded IDE sync failures named that artifact.
The wrapper uses Gradle `9.8.1`, while existing metadata covered the earlier bundled Groovy family.

The repair adds 12 exact digests in [`verification-metadata.xml`](../../gradle/verification-metadata.xml).
Each artifact first caused a recorded native rejection.
Publisher bytes, published SHA-256 files, and actual Gradle cache bytes agree.
All 1,197 existing component records and the trust configuration remain unchanged.
Strict dependency verification remains enabled.

The identical native request then completed import under preparation request `1a3542e4-ec05-459f-921e-60776f480f0a`.
Preparation completed at admission after 11,029 milliseconds.
The Gradle daemon reported a successful build.
The semantic query then reproduced the work-limit rejection on the installed plugin.
Its read identity was `857efae8-771c-4c23-812a-bedbe35225ac`.
This transition proves the import repair separately from the local discovery change.

The complete import proof resides in `build/reports/callers-work-limit/import-fix-proof.json`.
Its referenced receipts preserve every failed retry, exact publisher URLs, artifact digests, trust-policy comparison, and native preparation events.
The later authorized Host installation and replay appear in the installed acceptance section.

## Repository checks

The following command passed after the serial module and native suites.

```sh
mise exec -- ./gradlew :symbol:intellij:check :runtime:hosted:compileKotlin \
  verifyKastArchitecture verifyJsonContracts knowledgeImpact \
  verifyKnowledgeBase verifyDocumentationGlossary --console=plain
```

The JSON guard examined 628 fingerprints without violations.
The knowledge guard examined 65 concepts without citation issues.
The documentation tooling suite passed 345 cases.
An optional earlier `detektTest` run failed with 191 findings, mainly existing `NamedArguments` findings.
The normal module `check`, main `detekt`, and format guards passed.

The candidate plugin assembly also passed.

```sh
mise exec -- ./gradlew :runtime:hosted:hostedPlugin --console=plain
```

The local archive resides in `runtime/hosted/build/distributions/kast-ide-hosted-v0.50.0-153-g877cf9a4d2-idea-262.zip`.
Its SHA-256 is `afd0cfb7944ad2e3bb79ff53d480744c1b0f7673e34b25e4e67b0674d9fd72d6`.
This archive contains the local source changes and does not represent a published revision.

The pinned prose assessment returned `ASSESSED` with advisory findings.
The review keeps its grade-8 advisory because exact error names and compiler terms remain necessary.
The wiki keeps existing diction, sentence-length, spelling, modal, and claim findings outside the new paragraphs.
The wiki reports reside in `build/reports/callers-work-limit/vale.json` and `build/reports/callers-work-limit/prose-assessment.json`.
The final review reports use those same filenames under `build/reports/callers-work-limit/installed-acceptance/`.
These assessments do not establish reader comprehension.
