# Error returns and the known-empty control

The new assertion drafts remain outside active source sets.
Compilation, native assertions, retained-query assertions, and milestone completion are **NOT RUN** for these drafts.
The original sources, ten manifests, acceptance seeds, and existing assertions remain unchanged.

## Original Error continuations

The frozen oracle gives `C2.5` and `C2.6` separate Success and Error routes.
The existing C2.4 selector ends at the source `toClientError` return.
The existing C2.5/C2.6 selector asserts the Success route only.

The proposed positive selector is `testOriginalS2ErrorRowsShareOneRetainedCarrierRouteThroughC26`.
It uses `producerS2`, the original `getOfferReturn` target, existing grants, original exclusions, and the existing declaration recipe.
`NativePacketQueryCapture` remains the sole query and retained-evidence owner.

One contiguous witness must contain these source facts:

- The original execute-result predicate identifies `Outcome.Error` at `OffersClient.kt:769:954` with its actual DISPATCH slot and context.
- Source `map` reads the Error payload and constructs the replacement `Error.value` before its matched return.
- Original `mapError` supplies its declared `error` parameter to the actual `transform(value)` call at `Outcome.kt:577:593`.
- The source mapper reads `WireError.code` and stores the exact `ClientError.code` field, as the existing C2.4 oracle states.
- The normal callback return at `OffersClient.kt:1112:1133` reaches the same builtin invocation through its exact frame.
- Source `mapError` constructs `Error.value` with the `ClientError.code` suffix before its matched return.
- The source client return carries `Error.value.code` through the exact production implementation frame to the original caller result.

Both constructions need source constructor evidence.
An identity edge between replacement carriers cannot satisfy the positive.
The existing C2.4 oracle must also pass on a witness that passes the continuation assertions.

The separate forbidden selector is `testOriginalS2ErrorReturnsForbidSuccessCarrierAndCrossCallWitnesses`.
It examines emitted witnesses for debug-message substitution, lost Error guards, Success carriers with client-error payloads, and another caller result.
It requests only the original positive destination.
An unresolved requested site supplies no absence proof.

The drafts reside in `drafts/segment6/error-carrier-return`.
The proposed enrollment adds one narrow source method and two named cases to the existing runtime fixture owner.
Runtime diagnostics and evidence adapters remain unchanged.

## Original constant source

The original source uses `FeatureError.reason`.
Its literal occupies `OffersProcessor.kt:1007:1017`, inside constructor `985:1018` and callback `983:1020`.

The two proposed native cases reside in `drafts/segment6/no-offer`:

- `testOriginalNoOfferLiteralHasItsActualZeroArgumentCallbackAndReasonParameter` establishes real K2 source, type, argument mapping, and anonymous-owner facts.
- `testOriginalNoOfferLiteralContributesToItsExactFeatureErrorReasonField` starts at that exact literal occurrence through the native position adapter.

The positive needs the actual constructor argument at declared index zero and the exact immutable `reason` storage field.
It preserves the literal's owner, predicates, and stack through that local route.
Constructor storage adds the exact `reason` path segment.
This control does not replace the original S2 invocation seed.
It does not establish the callback return, empty-list decision, or feature-error return.

The separate proposed runtime case is `testOriginalS2EmittedInputWitnessesNeverAcquireTheLiteralNoOfferReason`.
It keeps the full original S2 request and forbids input provenance at that exact constant constructor and field.
A legitimate backend-code route to another `FeatureError.reason` constructor remains legal.
This assertion concerns emitted witnesses only.

## Exact known-empty gaps

`FlowControls.sourceKnownEmpty` supplies `Outcome.success(Envelope(WireResponse(emptyList()), 204))` in frozen source.
The existing caller draft uses a real source API implementation and the original client and processor bodies.
Its runtime assertion expects `Outcome.error(FeatureError(reason = "NO_OFFER"))`.
Neither source syntax nor that runtime result establishes a static collection fact.

Current `ReviewedValueModel` owns `FirstOrNull` and `Let`.
`SelectedElementCondition` preserves the exact receiver, result, and selected index zero.
It grants no nonempty or NonNull fact.
Current predicates provide resolved subtype and NonNull facts.
The inspected value contracts provide no collection-cardinality fact or null-result fact.
They cannot decide whether the conditions hold for a known empty collection.

The complete empty control still needs these exact proofs:

- Bind the actual `emptyList()` declaration and artifact to the same `WireResponse.links` collection that reaches original `response.links` at `939:953`.
- Preserve that fact through source mapper storage, both replacement carriers, the matched client return, and the immutable response entry.
- Prove the actual source supplier route through `Call.adapt`, rather than infer it from lexical containment or runtime injection.
- Establish that the exact selected-element condition cannot produce a non-null value for that collection.
- Reach the original literal callback, its matched zero-argument invocation, `Outcome.error`, the second destructuring operation, and the matched Halt/catch reconstruction.

The final positive needs `getOffer` return path `Error.value.reason` from the literal source.
The separate forbidden assertion needs no satisfiable input-to-link witness under the exact Success and NonNull conditions.
Absence from `RELATIONSHIP_UNPROVEN` cannot satisfy either obligation.
The local literal control supplies only the first constant-storage proof.

## Owners and scope

The packet, corrected oracle, original fixture sources, and existing typed contracts own the expected rows.
The new sources assess existing ports and detached evidence.
They add no product model, source fact, query coordinator, or public producer variant.
The fixture guide needs no change because the commands, ownership, and local rules remain the same.
