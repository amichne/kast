# Change contract module guide

`:change:contract` owns the closed semantic change-intent family plus detached evidence, plan
identity, obligation, and verification contracts. It performs no host observation, persistence,
or source effect.

## Dependency boundary

- Production depends only on `:kernel`, `:workspace:contract`, `:symbol:contract`,
  `:relation:contract`, `:traversal:contract`, `:diagnostic:contract`, and serialization
  support.
- Do not import IntelliJ, workspace implementation, JDBC, filesystem, transport, legacy
  `analysis-api`, backend, adapter, service, or callback types.
- Compatibility evidence is detached canonical JSON with a verified digest; it is not generic edit
  authority and may be decoded only by a named legacy transport boundary.

## Contract invariants

- Raw add-declaration requests refine into canonical-root, canonical-target, exact-preimage, and
  normalized declaration intent or a finite rejection.
- `PlannedAddDeclaration` binds one exact G0, target owner and source-root provenance, exact before
  and expected after images, a singleton declared write set, operation obligations, expected
  semantic delta, verification terms, and all detached compiler evidence.
- Revalidation admits only a coherent current observation that still matches generation, target
  identity, owner and scope, exact content, authored provenance, and writability. Its output carries
  exact recovery material but no write capability, and every rejection is fixed to `NOT_BEGUN`.
- Plan identity is the SHA-256 of canonical serialized identity material. Decode rejects malformed,
  non-canonical, or tampered bytes.
- No plan retains a path handle, file handle, PSI value, document, callback, or mutation capability.
- Editable-target admission retains one published root/generation/state, exact symbol selector,
  exact content identity, unique Gradle owner, and authored source-root provenance. The returned
  value is planning eligibility only and grants no source effect.
- Add-declaration planning consumes only complete detached relation, traversal, and diagnostic
  variants for that exact target lease. The plan normalizes evidence order before identity,
  retains one exact insertion and every closed obligation, and grants no apply capability.
- RenameSymbol planning admits one changed Kotlin identifier and a deterministic compiler-grounded
  occurrence set. Every replacement retains its exact expected old identifier, and the resulting
  plan implements the same sealed `ChangePlan` contract as AddDeclaration.
- AddFile planning admits one canonical `.kt` path strictly inside a uniquely owned authored
  source root and whole-file source text. Its plan retains an explicit absent precondition and one
  typed file-creation mutation; physical absence is proved again during apply.
- ReplaceDeclaration planning binds the compiler-grounded target range to its exact extracted
  declaration preimage, admits a distinct canonical replacement declaration, and retains both in
  one typed whole-declaration mutation.

## Verification ladder

1. Run `./gradlew :change:contract:test --tests '*PlannedAddDeclarationTest'`.
2. Run `./gradlew :change:contract:test`.
3. Run `./gradlew :change:plan:test :change:intellij:test`.
4. Run `./gradlew verifyKastModuleGraph verifyForbiddenEffects`.
