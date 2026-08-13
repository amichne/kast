# IntelliJ change planning adapter guide

`:change:plan:intellij` assembles detached add-declaration plans from one injected IntelliJ evidence
source. The source must finish every PSI, index, model, and file observation before returning its
detached evidence.

## Dependency boundary

- Production depends only on `:change:contract`, `:change:plan:spi`, and `:workspace:contract`.
- This read adapter must never depend on a source-write, apply, recovery, journal, transport,
  aggregate backend, or legacy `analysis-api` implementation.
- Never retain `Project`, PSI, VFS, document, scope, analysis-session, or callback values.

## Adapter invariants

- The evidence intent must equal the admitted operation intent exactly.
- Planning either returns one deterministic `PlannedAddDeclaration` or a finite typed rejection.
- Planning performs no source write, persistence, refresh, import, graph build, or process control.

## Verification ladder

1. Run `./gradlew :change:plan:intellij:test --tests '*IntellijAddDeclarationPlannerTest'`.
2. Run `./gradlew :change:contract:test :change:plan:spi:test :change:plan:intellij:test`.
3. Run the legacy indexer add-declaration routing test.
4. Run `./gradlew verifyKastArchitecture --configuration-cache`.
