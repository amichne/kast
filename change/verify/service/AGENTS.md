# Change verification service module guide

`:change:verify:service` composes a KIP-034 applied-unverified capability, one exact source-files
workspace transition, the scoped KIP-035 IntelliJ verification port, and the durable v5 journal
transition. It owns no IntelliJ object, source mutation, workspace worker, SQLite implementation,
or public transport.

## Invariants

- The transition request is one exact workspace-relative target and the already-proven postimage
  hash; the service never rereads source to manufacture the claim.
- The exact returned publication is passed unchanged through context observation, verification,
  and receipt persistence.
- Every failure retains the strongest applied, publication, context, or verified observation
  reached. Only durable v5 success removes recovery authority from the outward result.
- Expected effect failure is closed typed data; no nullable, Boolean, string, sentinel, or arbitrary
  exception is an outward failure protocol.

## Verification

Run `./gradlew :change:verify:service:test --tests '*AddDeclaration*Verif*'`.
