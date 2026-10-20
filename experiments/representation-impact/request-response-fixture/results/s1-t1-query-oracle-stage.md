# Original S1 and T1 query assertions

Five new cases remain in `drafts/segment6/s1-t1-query`, outside active source sets.
Compilation, native query assertions, and milestone completion are **NOT RUN** for these cases.
The staged patch extends existing fixture owners only.
It adds no production rule, source model, alternate query coordinator, or diagnostic adapter.

## Original S1

The exact seed is the original `ClientRequest` constructor at `OffersProcessor.kt:707:792` inside the genuine `sequential` body.
The source adapter issues its exact constructor and enclosing method tokens.
The query requests only the positive API body argument.

`testOriginalS1RowsC11ThroughC14ReachTheExactOpaqueBodyArgument` needs one contiguous witness:

- C1.1 reaches `client.readOffers` argument `request` at declared index zero under the original anonymous owner.
- C1.2 enters the exact production implementation and its real `request` formal.
- C1.3 enters the source extension mapper, reads `ClientRequest.destination`, and stores `WireRequest.destination` at declared index zero.
- The matched mapper return preserves the wire-field path.
- C1.4 reaches `OffersApi.readOffers.body` at declared index one and keeps the exact `EXTERNAL_CALL` qualification.

`testOriginalS1ProductionWitnessesForbidResponseLogAndFieldSubstitution` examines emitted witnesses separately.
It forbids production response fields, response results, the `getOffer` return, and both Logger argument sites.
It also forbids `sessionToken` substitution for `WireRequest.destination` and input provenance for the constant channel.
An unmatched requested site cannot satisfy those assertions.

The existing `NativePacketCompositePathTest.testOriginalS1OutgoingReachesTheOpaqueApiBody` supplies historical native prefix evidence.
It does not establish these new retained-query cases.

## Real test-source control

Both T1 domains import the original four production files and the frozen `EchoOffersClient.kt` test file.
The proposed fixture change assigns distinct production and test roots under `src/main` and `src/test`.
Default cases keep their existing single production root.
The existing query capture accepts an explicit typed source domain and preserves the current production default.

`testOriginalS1ProductionReportsTheExactKnownEchoExclusion` uses production scope with both roots present.
It needs the real Echo declaration, exact source occurrence, typed `SOURCE_DOMAIN` exclusion, and imported-model membership authority.
It also needs production implementation evidence and separate forbidden response and Logger assessments.
An absent Echo entry or a rejected seed cannot satisfy the exclusion assertion.

`testOriginalS1CombinedEchoReachesTheFullGuardedFeatureReturn` uses the same original seed and combined source scope.
It needs both exact implementation targets, their complete target-set evidence, and their distinct source-root ownership.
One contiguous Echo witness must cross these exact steps:

- Read `ClientRequest.destination` inside the real Echo method.
- Apply the reviewed single-element `listOf` transformation to the original call at `EchoOffersClient.kt:334:361`.
- Store the element-zero path in `ClientResponse.links` and preserve `Success.value.links[0]` through the matched Echo return.
- Cross the original source `component1` and `bind` operations.
- Apply the exact first-element selection and preserve its explicit selected-element condition.
- Store `FeatureResponse.link` and reach the original `getOffer` return path `Success.value.link`.

The full feature witness needs Success on the exact caller result.
It also needs NonNull on the actual checked `this` occurrence at `Outcome.kt:1622:1626`.
The assertion ties that predicate to the selected result through the matched source receiver entry and whole-value local read.
It does not relocate the predicate to the caller or accept an unrelated NonNull condition.

`testCombinedWitnessesKeepProductionOpaqueAndForbidEchoFieldAndLogSubstitution` examines emitted witnesses separately.
Production paths cannot borrow the Echo response route.
Echo link witnesses cannot substitute `sessionToken` or omit their exact guards.
Neither implementation can acquire request-to-Logger provenance.

## Missing semantic contracts

The existing library-binding capture proves the exact single-element `listOf` overload, binary affiliation, digest, and version.
Its canonical signature has no receiver, one fixed `T` parameter, and one type parameter.
Its identity is `canonical-signature-sha256-v1|36cbb72898977f3dac6a92bc3191ff76013f2302a3ba6abb01dee2416c1193f8`.
That capture establishes no modeled element transfer.

Current reviewed models cover `FirstOrNull` and `Let`.
They provide no `listOf` argument admission or element-insertion rule under the original library exclusion.
The T1 draft needs an actual reviewed application at that exact source argument and result.
It uses existing generic application fields to state the missing outcome without inventing a new model factory or rule identifier.
No model pass follows from a detached matcher or captured signature.

The corrected oracle assigns the Authorization header route to C1.5.
C1.3 remains the destination-to-wire mapper row.
The header key is the literal `Authorization` at `OffersClient.kt:1238:1253`.
The distinct value template occupies `1257:1279`, with the token reference at `1266:1278`.
The token must derive the header value through the template, the second Pair component, and the corresponding map value.
It cannot become provenance for the constant key or count as an unchanged string value.

Current contracts provide no reviewed template derivation, Pair-value assembly, or map key-to-value association rule.
The current value-path vocabulary has fields and element segments, without a key-specific map-value fact.
The native capture of `to` and `mapOf` supplies overload inputs only.
C1.5 remains **NOT ENROLLED** until the central reviewed contract states those facts.

All proposed cases preserve the original grants, generated-source exclusion, library exclusion, strict completion, and retained-read work assertions.
An opaque production path keeps its unresolved obligation even when the combined domain contains a useful Echo path.
Full known-empty proof and installed-host evidence remain outside this stage.
