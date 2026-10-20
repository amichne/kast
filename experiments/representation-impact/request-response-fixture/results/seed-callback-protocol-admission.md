# Seed callback diagnostics

The seed path uses the existing `CallbackFlow` evidence.
It keeps `CALLBACK_FLOW_UNPROVEN` and the complete qualified flow.
The public request syntax stays unchanged.

`ValueFlowObligation.fromCallback` still rejects an empty stack.
`fromSeedCallback` admits that context through the full bound supplier proof.
`ValueSeedCallbackQuestionTest` owns the scope-substitution control before projection.

`ImpactCallbackIdentityTest` now calls `admitsCallbackQuestion` for its empty-stack rejection.
Its former assertion called `admitsObligation`.
This assertion target changed because seeded diagnostics extend `admitsObligation`.
The prior bytes and hash appear in `evidence/segment5-seed-callback-before-ImpactCallbackIdentityTest.kt.txt` and `segment5-seed-callback-conservation-before.sha256`.

`ImpactDeclarationReferenceDocument` omits scope and constraint fields.
Wire admission cannot prove equality for those fields.
The core factory owns that proof.
Decoded output grants no compiler capability.

The seven new cases are **NOT RUN** at this source snapshot.
They cover encoded proof, forbidden substitutions, finite qualifications, and strict completion.
This snapshot establishes no native behavior.
