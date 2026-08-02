# Indexer module guide

`indexer` owns Kast's one compiler-backed runtime. It starts or reuses an
isolated IntelliJ Platform process, imports the Gradle model, executes K2 and
PSI analysis, and writes persistent index evidence.

## Runtime boundary

- `KastIndexerMain` is the application-classloader launcher. Keep it free of
  IntelliJ, Kotlin-plugin, Gradle-plugin, and Kast server types.
- `META-INF/plugin.xml` is a private classloader descriptor for the isolated
  indexer process. It is not a user-installable or publishable IntelliJ plugin.
- Keep indexer implementation and server jars under
  `idea-home/plugins/kast-indexer/lib`. Do not put them in `runtime-libs`.
- Preserve `VerifyClasspathLayoutTask` proof whenever packaging changes.
- A bare checkout is normal. Import the Gradle model and wait for smart mode
  before accepting compiler-backed evidence.

## Evidence boundaries

- `PsiSourceIndexScanner` owns semantic Kotlin package extraction. Convert PSI
  results to host-neutral `IndexedPackageEvidence` before persistence.
- No `PsiFile`, `KtFile`, IntelliJ `FqName`, or other platform type may cross
  into `index-store`.
- Missing project-model evidence must fail closed or remain typed as unproven.
  Do not convert missing evidence into an empty complete inventory.
- Capture compiler relationship results within the same read epoch that proves
  their semantic generation. Do not retain PSI or analysis-session objects in
  continuation state.
- `RunningAnalysisServer` owns backend closure. Cancel indexing, close the
  server/backend, then close the separately owned source-index store.

## Verification

Run `./gradlew :indexer:test` for source changes. Packaging changes also require
`./gradlew :indexer:verifyPortableDistLayout :indexer:portableDistZip`.
Relationship changes require state-cap, generation, continuation, paging, and
zero-provider-work subject-kind tests. Workspace inventory changes require the
focused inventory, paging, and Gradle-provenance tests.
