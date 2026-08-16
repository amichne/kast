# Change plan service guide

`:change:plan` owns pure admission and construction services for future mutation plans. KCS-014
materializes only proof that an exact semantic target is eligible to enter planning; it does not
construct a plan or grant an effect capability.

## Dependency boundary

- Production depends inward on `:change:contract` only.
- Do not import IntelliJ, filesystem effects, persistence, journal, apply, verify, recovery,
  transport, legacy `analysis-api`, or callback types.
- Generated, unknown-provenance, escaped, ambiguously owned, stale, and wrong-owner targets fail
  closed before planning.

## Verification ladder

1. Run `./gradlew :change:plan:test --tests '*MutationTargetAdmissionTest'`.
2. Run `./gradlew :change:plan:test`.
3. Run `./gradlew verifyKastArchitecture --configuration-cache` when module policy changes.
