# Change plan service guide

`:change:plan` owns pure admission and construction services for the closed change-intent family.
KCS-014 proves that an exact semantic target is eligible to enter planning; KCS-015 establishes
AddDeclaration and KCS-019 extends the same `ChangePlan` surface one intent at a time.

## Dependency boundary

- Production depends inward on `:change:contract` only.
- Do not import IntelliJ, filesystem effects, persistence, journal, apply, verify, recovery,
  transport, legacy `analysis-api`, or callback types.
- Generated, unknown-provenance, escaped, ambiguously owned, stale, and wrong-owner targets fail
  closed before planning.
- Qualified, rejected, absent, cross-generation, or wrong-target relation, traversal, and
  diagnostic evidence fails closed before plan construction.
- Plan identity is independent of evidence enumeration order, and this module owns no apply,
  journal, persistence, verification, or recovery effect.
- RenameSymbol plans require one changed admitted identifier and a deterministic complete
  compiler-grounded occurrence set for the exact target.
- AddFile plans require one authored source-root-owned Kotlin path and retain a whole-file
  postimage behind an explicit absent precondition.

## Verification ladder

1. Run `./gradlew :change:plan:test --tests '*MutationTargetAdmissionTest'`.
2. Run `./gradlew :change:plan:test --tests '*AddDeclarationPlanTest'`.
3. Run `./gradlew :change:plan:test`.
4. Run `./gradlew verifyKastArchitecture --configuration-cache` when module policy changes.
