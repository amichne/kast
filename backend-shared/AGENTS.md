# Shared backend agent guide

`backend-shared` owns IntelliJ Platform and Kotlin PSI analysis reused by the
IDEA and headless backend hosts. PSI is an intentional implementation detail of
this module; host-neutral contracts leave the module before persistence.

## Ownership

- Keep reusable PSI-backed analysis here rather than pretending the module is
  independent of IntelliJ. Backend-host lifecycle, transport, and CLI concerns
  remain with their owning modules.
- `PsiSourceIndexScanner` owns semantic Kotlin package extraction. Read
  `KtFile.packageFqName` or equivalent structured compiler evidence and convert
  it inside this module to `IndexedPackageEvidence.ProvenRoot`, `ProvenNamed`,
  or `Unproven(reason)`.
- Pass only host-neutral `IndexedPackageEvidence` through `FileIndexUpdate` to
  `index-store`. No `PsiFile`, `KtFile`, IntelliJ `FqName`, or other IntelliJ or
  Kotlin PSI type may cross that dependency boundary.
- `SourceFileIndexParser` may continue to provide declaration parsing, but a
  nullable or failed text-parser package result cannot prove the root package.
  Preserve that case as typed unproven evidence.
- Keep escaped keywords, backticked non-identifiers, and Unicode package names
  in their canonical Kotlin semantic form rather than reconstructing them from
  source text.

## Verification

- Run `./gradlew :backend-shared:test` for local PSI-analysis changes.
- For source-index package evidence, start with
  `./gradlew :backend-shared:test --tests '*PsiSourceIndexScannerTest*'` and then
  run `./gradlew :index-store:test --tests '*SqliteSourceIndexStoreTest*'`.
- Final cross-host acceptance also requires `./gradlew :backend-idea:test`.
