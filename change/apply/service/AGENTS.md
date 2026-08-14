# Change apply service module guide

`:change:apply:service` admits the KIP-033 recovery proof, invokes one selected apply adapter, and
proves that the observed postimage and changed-document set equal the approved plan. It owns no
IntelliJ object, filesystem effect, persistence adapter, publication, or verification effect.

## Invariants

- No executor call occurs until durable recovery and its recovery-prepared journal record agree.
- Success requires the exact expected postimage and singleton declared write set.
- Every failure preserves whether source mutation had not begun or had begun.

## Verification

Run `./gradlew :change:apply:service:test --tests '*AddDeclaration*Apply*'`.
