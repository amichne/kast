# IntelliJ change apply adapter guide

`:change:apply:intellij` owns the KIP-030 pinned semantic add-declaration write protocol. It may
hold IntelliJ PSI, VFS, and document objects only during one preparation-to-command invocation.

## Invariants

- Cancellation, smart mode, exact target resolution, writability, preimage, parsing, and document
  capture finish before entering the write command.
- The command only adds the prepared declaration PSI, reformats whitespace, and commits the target
  document. Save and changed-document observation happen afterward.
- No search, index work, refresh, import, persistence, verification, save, or reference shortening
  occurs inside the write command.
- Rejections before the command prove mutation not begun; every later failure proves mutation begun.

## Verification

1. Run `./gradlew :change:apply:intellij:test --tests '*AddDeclaration*Apply*'`.
2. Run the matching indexer fixture and KIP-030 protocol ledger test.
