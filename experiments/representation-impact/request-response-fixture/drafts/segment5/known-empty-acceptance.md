# Known-empty pipeline control

This draft adds a source caller for `sourceKnownEmpty()` through the original client and processor.
It does not replace the original S1, S2, or S3 acceptance seeds.
All new assertions remain **NOT RUN**.

`SourceKnownEmptyOffersApi.readOffers` returns the original `Call` with a source-defined `adapt` lambda.
`sourceKnownEmptyOffer` calls the original `OffersClientImpl` and `OffersProcessorImpl`.
No feature body or mapper body receives a copy.
The separate manifest freezes only this new caller.
The original four manifests stay unchanged.

## Positive controls

The runtime assertion expects exactly `Outcome.error(FeatureError("NO_OFFER"))`.
That assertion exercises the original empty-result path through `firstOrNull`, `requireNotNull`, and `OutcomeScope.bind`.
Runtime success does not prove native cardinality or absent semantic witnesses.

The native prefix starts at the exact `sourceKnownEmpty()` call inside the source-defined `adapt` lambda.
One contiguous retained path must reach the original `response.links` operand of `firstOrNull`.
The path must read `WireResponse.links`, store `ClientResponse.links`, and read that field in the original processor.
The draft evaluator asserts this prefix through existing path documents.
It does not declare an empty-list proof from the source helper's name.
It does not yet assert the full `NO_OFFER` route.

## Original seed and exact source context

The original S2 query must keep the `execute()` result at `OffersClient.kt:769:954`.
The supplemental `sourceKnownEmpty()` producer supplies an independent source control.
It cannot replace S2 or assign emptiness to S2 from the helper name.

The source caller must bind its exact API override, returned `Call`, stored `adapt` lambda, and actual builtin invocation.
That lambda's normal return must match the original `execute()` result in the same call context.
The stored function-value origin still lacks its admitted source route.
No default request-to-response continuation can replace that proof.

## Collection contract

The shared model must bind `emptyList` and `firstOrNull` to their exact K2 overloads and pinned library artifact.
An admitted empty-list fact must preserve its exact collection identity through the source route.
For that same receiver, `firstOrNull` must return null without an element contribution.
No `Element(0)` or `AnyElement` input can supply a result under that empty fact.
A known nonempty receiver and index-one input form the nearest contrasting controls.

The original `firstOrNull` result must remain the subject of the later `NonNull` predicate.
The accepted subject role still needs the actual native `firstOrNull` and `requireNotNull` capture.
An unrelated `NonNull` predicate or a predicate count cannot discharge this rule.

The current shared model does not yet supply this collection fact contract.
The stored `Call.adapt` route also needs the exact function-value origin proof.
These unmet contracts keep the native known-empty acceptance status **NOT RUN**.

The current owners expose specific gaps:

- `ModelValuePosition` has only `Result` and `Argument` positions.
- Model inputs admit only `ValueRole.Argument` with `ValueAccessPath.Whole`.
- `RepresentationEvidence.transfer` changes the current state to `Unknown` after a field read or constructor-field transfer.
- `ValuePathSegment.Element` selects a path but does not prove a collection size.
- `ValuePredicates` carries `Subtype` and `NonNull`, with no null or empty-collection fact.

The six native library captures include the exact `List<T>.firstOrNull()` overload.
They do not include the exact `emptyList()` overload.
That binding and its artifact affiliation remain missing.
The Empty fact must name the same collection through `WireResponse.links`, `Envelope.body`, `Success.value`, and `ClientResponse.links`.
It must keep each matched call context and the original execute-result Success subject.
An unrelated collection or a same-typed field cannot supply that fact.

## Full positive error route

The native control still needs the positive `NO_OFFER` route through the original source.
The exact empty receiver at `response.links`, range `939:953`, must yield null at `response.links.firstOrNull()`, range `939:967`.
The null decision must select the error callback of the same `requireNotNull` call.
Its source constructor at `985:1018` creates `FeatureError(reason = "NO_OFFER")`.
The callback return must enter `Outcome.error` and the second source-defined `component1` and `bind` calls.
The thrown Halt payload must match the actual `Outcome.sequential` catch.
The final `getOffer` return must keep `Error.value.reason` with the exact `NO_OFFER` origin.

The empty collection does not supply the constant string's data.
A full positive needs the null decision and that separate constant origin in the same source context.
An input-to-constant transfer cannot replace this proof.
The current draft evaluator and fact record do not express or assert this full route.

## Separate forbidden witnesses

The original S1 request must first reach the real `SourceKnownEmptyOffersApi.readOffers` body formal.
That positive source-entry assertion must use the exact override, parameter index one, parameter range, and name `body`.
A separate assertion rejects emitted `FeatureResponse.link` witnesses from that original request.
It never reads `RELATIONSHIP_UNPROVEN` as absence proof.

The original S2 empty-result exclusion also needs the admitted empty collection fact and the positive native prefix.
Only then can a separate emitted-witness assertion reject an empty-result contribution to `FeatureResponse.link`.
The current prefix assertion alone cannot discharge that exclusion.

The forbidden result concerns a satisfiable feature-link route under the exact Success and NonNull subjects.
For the proven empty receiver, null and NonNull on that same value cannot both hold.
The subject must keep range `939:967`, its actual native role, the genuine sequential body, and the matched receiver context.
An unrelated NonNull predicate or an empty result list cannot prove this contradiction.
Unknown branches must keep their unresolved status.

## Integration scope

The existing fixture owner must import the new source beside the four original files and `FlowControls.kt`.
The existing source owner must issue exact selectors and callback identities before capture.
The existing `NativePacketQueryCapture` keeps the fixed grants, strict completion, and zero retained-read delta.
No new explorer or model registry belongs to this draft.
