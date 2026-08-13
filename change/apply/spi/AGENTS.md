# Change apply SPI module guide

`:change:apply:spi` owns the detached add-declaration apply command, observed physical outcome,
finite failure protocol, and executor port. It owns no IntelliJ object, source write, journal
implementation, verification, or workspace transition. The command retains both the detached
KIP-033 durable recovery proof and journal-issued apply admission, but owns no recovery artifact
or recovery effect.

## Invariants

- The command carries one canonical planned add-declaration and no raw edit list.
- Physical observations carry exact postimage bytes and canonical changed-document identities.
- Every rejection carries the exact mutation progress reached by the physical adapter.

## Verification

Run `./gradlew :change:apply:spi:test --tests '*AddDeclaration*Apply*'`.
