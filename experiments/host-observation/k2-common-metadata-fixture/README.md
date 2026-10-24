# Common metadata reference fixture

Use this fixture to compare Kast reference queries across JVM classes and common Kotlin metadata.
The fixture keeps the known direct calls and the annotation cases that exposed `KaBaseIllegalPsiException`.
It does not contain a production workaround.

## Compile and import

Use the repository wrapper from the Kast repository root:

```sh
mise exec -- ./gradlew \
  -p experiments/host-observation/k2-common-metadata-fixture \
  --max-workers=2 \
  :jvmProbe:compileKotlin \
  :multiplatformProbe:compileKotlinJvm \
  :multiplatformProbe:compileCommonMainKotlinMetadata
```

The documented command passed with all five tasks on the recorded JDK and Gradle versions.
Kotlin 2.4.21 and JDK 25 compile both modules.
The multiplatform module declares JVM and JS targets, which give `commonMain` a common metadata dependency.
The JVM module supplies the control.
[Build settings](build.gradle.kts) and [target settings](multiplatformProbe/build.gradle.kts) own these choices.

Copy this fixture to a temporary directory before IDE import.
Use the repository wrapper with `-p` set to that directory.
Import the temporary directory as a Gradle project in the selected IntelliJ installation.
Examine the imported library roots before the semantic queries.
The intended contrast is a `kotlin-stdlib` JVM jar against a transformed `commonMain` `.klib`.
Attached sources alone do not establish this contrast.

## Independent oracle

[The case manifest](case-manifest.tsv) names each target, expected named caller, and forbidden named caller.
[The source identity table](source-identity.tsv) records the SHA-256 values for eight files.
Each file appears unchanged in both modules.
The expected results come from those source calls, not from Kast output.

For each case, discover the exact `FUNCTION` in its case directory.
Use source set `main` for `jvmProbe` and `commonMain` for `multiplatformProbe`.
Apply the same directory and source set to `REFERENCES` and `CALLERS`.
Each complete result must include the known direct call or its named caller.
The complete `CALLERS` result must exclude the forbidden caller.
Imports and annotation injection can add reference occurrences, so one known call does not establish an exhaustive reference count.
The `replacewith` case examines annotation injection separately from an ordinary string.
The template and alias cases protect eligible references against proposed search exclusions.

Record the selected symbol, read ID, outcome, exact host build, and source hashes.
Record query rejection separately from setup failure or a missing expected witness.
A returned preview does not establish complete results.

## Recorded installed results

The campaign ran on October 24, 2026, with IntelliJ IDEA `IU-262.10968.63` and Kotlin plugin `262.10968.63-IJ`.
The installed Kast version was `0.0.3708`, from source commit `e7075cbb0cb42bede2976136182c728f3ebece82`.
Oracle GraalVM JDK `25.0.2+10.1` compiled the fixture with Gradle `9.8.1`.

The first fixture declared only a JVM target in its multiplatform module.
Its JVM and `commonMain` roots both used a `kotlin-stdlib` JVM jar.
All 42 calls completed: discovery, `REFERENCES`, and `CALLERS` for seven cases in two modules.
The known named callers passed their source oracle.

The second fixture added a JS target without a Kotlin source or version change.
Its imported common library used a transformed `.klib`.
All 42 calls ran, with 39 complete results and three incomplete results:

| Module | Case | Query | Result |
| --- | --- | --- | --- |
| `multiplatformProbe` | `deprecated` | `REFERENCES` | `KaBaseIllegalPsiException` |
| `multiplatformProbe` | `replacewith` | `REFERENCES` | `KaBaseIllegalPsiException` |
| `multiplatformProbe` | `replacewith` | `CALLERS` | `KaBaseIllegalPsiException` |

The JVM cases passed.
The three failures reached `ObservedRelationSearch.referenceSearch` and `IntellijReferenceInventory.prepare`.
The exception named a source session, a library module, and a `KtParameter`.
These results establish a controlled Kast reproduction, not a fixed defect.

A separate direct `ReferencesSearch` probe then completed eight calls with one target witness each.
It ran both annotation cases in both modules twice, without Kast or an outer `analyze` session.
It used `GlobalSearchScope.filesScope` for one file and `ignoreAccessScope=false`.
That control did not reproduce the failure independently of Kast.
A later scope control ran the nested annotation case with five scope forms in both modules twice.
All ten JVM calls completed, and all ten common-source calls threw `KaBaseIllegalPsiException`.
Bare `FileScope`, a platform delegate, the actual Kast admission wrapper, and two intersection variants shared this result.
Each scope admitted one file and its owner module, and excluded libraries.
The bounded attachment header identified `04_kotlin.knm` as the parameter file.
It did not expose `isCompiled`, so that field remains unmeasured.

This control reproduced the provider failure through a direct platform call.
The earlier eight passing calls remain a material contrast.
The campaign did not control cache history or establish recurrence after a fresh IDE restart.
A deterministic failure across all runs therefore remains unproved.
[The recorded outcomes](installed-results.tsv) preserve both results without source payloads.

The private campaign receipts include both `summary.json` files, `direct-native-references.json`, and `direct-native-scope-controls.json`.
The temporary campaign root is session evidence and is not a portable repository dependency.
Raw exception attachments and source payloads do not belong in public logs.

## Source path and limits

[Reference search](../../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/ObservedRelationSearch.kt) uses `ReferencesSearch` with `ignoreAccessScope=false`.
[File enumeration](../../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/RelationFileEnumeration.kt) partitions small scopes and intersects each file with the admitted scope.
[Native scope admission](../../../relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/NativeRelationSearchScope.kt) wraps that scope and preserves its enumeration.
The direct control uses the concrete platform scope instead.

The installed provider stack reaches `JamReferenceContributor`, Kotlin light annotations, and `KaFirPsiSymbolKt.createKaValueParameters`.
The provider creates its own session from the annotation use-site module.
The [Analysis API contract](https://github.com/JetBrains/kotlin/blob/master/analysis/analysis-api/src/org/jetbrains/kotlin/analysis/api/KaSession.kt) limits PSI inputs to the current session.
The stack identifies the failed conversion but does not prove that an outer session change fixes the defect.

The [repository native fixture lane](../../../relation/intellij/build.gradle.kts) pinned IDEA `262.9437.185` at Kast commit `8925207821c9512c4d5bafaac6a5956447ced2ba`.
It rejected the installed SDK with `BUILD_MISMATCH` before a native assertion ran.
That setup rejection is not product RED.
The installed-host reproduction therefore remains a separate evaluation.
No provider exclusion, exception suppression, binary patch, or completeness change forms part of this fixture.
