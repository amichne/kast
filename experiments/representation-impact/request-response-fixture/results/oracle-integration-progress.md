# Oracle integration snapshot

M1 is **PASSED** within the recorded source and analysis scope at `2026-10-20T03:28:31Z`.
M2 and M3 remain **NOT PASSED**.
V0–V3 and V8 passed both directions through real K2 ports, `QueryService`, the existing `QueryStateStore`, and kept protocol `ValuePaths`.
Native S1 reached C1.1–C1.4.
Native S2 stopped before source `Outcome.map` receiver entry and reported `UNMODELED_CALL`.
Later callback-payload assertions remain **NOT RUN**.
The parent continues implementation after this snapshot.

## Source identity and limits

The current `HEAD` remains `543509a7f4d22d6ec850f52b70c898bca478c37d`, with uncommitted implementation changes.
The commit identifies frozen inputs, but it does not identify every executed implementation byte.
The native runs use private indexed projects and an authored model of production source roots.
They do not establish a Gradle import, installed-host admission, or public Tool RPC capture.

The [initial manifest](../initial-freeze.sha256) preserves the original composite, V0 body, and corrected oracle.
Its digest remains `88eb9a636cd55fb525f12f2b308c9dbdc71e3e94075c155f4f1fd0858695752c`.
The [upstream manifest](../upstream-inputs.sha256) records the supplied packet and audit digests.
The original constructor and nested-lambda seeds preserve their original bytes.
Direct SHA-256 comparisons passed all seven initial entries, twelve derived entries, and seven runner entries before the composite run.

The [M1 query manifest](../evidence/native-query-m1-inputs.sha256) records the exact oracle, runner, manifests, and all five plain source variants.
Its digest is `4c343dcb152ec6c196ecec28336221917de3cca80a4d89126f9eb595ead31aaa`.
The [revision receipt](../evidence/native-query-m1-revision.txt) records the commit and uncommitted-source limit.
Neither receipt identifies every implementation dependency.

## Evidence levels

| Mechanism | Expected behavior | Result and evidence |
| --- | --- | --- |
| Standalone Kotlin fixture | Compile controlled sources with Kotlin `2.4.21` and `-Werror`; preserve exact runtime fields and manifests | **PASSED**, six-second command in the [procedure record](../evidence/native-query-commands.tsv) |
| Detached source-flow oracle | Preserve fields, depth, basis, call context, and source evidence | **PASSED**, [ten source-flow cases](../evidence/contract-source-flow-oracle-green.xml) and [six storage cases](../evidence/contract-constructor-oracle-green.xml) |
| Detached implementation oracle | Preserve typed dispatch, ownership, stable frames, return isolation, and incomplete zero-target evidence | **PASSED**, [eleven frame cases](../evidence/implementation-contract-green-ValueImplementationCallOracleTest.xml) and [four obligation cases](../evidence/implementation-contract-green-ValueImplementationObligationOracleTest.xml) |
| Exact generated-property identity | Resolve `ClientRequest.destination`; keep function and ordinary-parameter controls | **RED** to **GREEN**, [one-case RED](../evidence/native-property-identity-red.xml) and [one-case GREEN](../evidence/native-property-identity-green.xml) |
| Native immutable fields | Admit stored immutable fields; reject abstract, mutable, and computed properties | **RED** to **GREEN**, [four-case RED](../evidence/native-field-storage-red.xml) and [four-case GREEN](../evidence/native-field-storage-green.xml) |
| Native implementation entry | Preserve concrete targets and production/test scope | **RED** to **GREEN**, [four-case RED](../evidence/native-implementation-red.xml) and [four-case GREEN](../evidence/native-implementation-entry-green.xml) |
| Native implementation return | Pop a fresh equivalent frame and reject another implementation frame | **PASSED**, [two return cases](../evidence/native-implementation-matched-return-green.xml) |
| Native plain routes | Preserve both directions for V0–V3 and V8, matched calls, fields, and fixed grants | **PASSED**, [ten route cases](../evidence/native-concrete-implementation-routes-final-green.xml) and [two controls](../evidence/native-direct-matched-return-final-green.xml) |
| Real V0 query and kept paths | Preserve both positives, forbidden-witness assertions, and exact-property controls | **PASSED**, [five V0 cases](../evidence/native-query-m1-v0-regression-green.xml) |
| Real V1 query and kept paths | Preserve both positives with static/concrete proof and forbidden-witness assertions | **PASSED**, [two V1 cases](../evidence/native-query-m1-v1-regression-green.xml) |
| Real V2, V3, and V8 queries | Preserve both directions, helper frames, nested seeds, formal indices, and forbidden witnesses | **PASSED**, [six cases](../evidence/native-query-m1-new-green.xml) |
| Native composite prefix | Reach C1.1–C1.4 and enter the source map receiver before C2.1 payload transfer | **FAILED**, [two cases](../evidence/native-composite-map-receiver-red.xml): S1 passed and S2 failed |
| Owning native guards | Pass ordinary tests, formatting, length, and static analysis | **PASSED**, 241 cases and gates in the [native procedure record](../evidence/source-transfer-commands.tsv) |
| Installed-host capture | Run the packet through an installed host | **NOT RUN** |

These selections cover distinct mechanisms and do not form one aggregate native suite.
The root also recorded 224 relation-contract cases and their owning policy gates as **PASSED**.
The [root procedure record](../evidence/root-integration-commands.tsv) preserves that result and earlier policy failures.
The later native route and query commands passed `detektNativeTest` without an exclusion.
The [source-transfer record](../evidence/source-transfer-commands.tsv) keeps earlier setup, policy, capacity, and semantic failures separate.

## Full-query assertions

The fixture copies an exact frozen plain variant into its private indexed project.
It resolves expected declarations and fields through the production exact-symbol port.
The protocol alone owns exploration, continuation, and the existing kept query state.
The request supplies no library model or transport continuation.
Each seed requests exactly one positive occurrence.
The declarations table supplies source-resolved metadata without more requested targets.

The new selectors passed six cases with no failures, errors, or skips in 51 seconds:

```sh
mise exec -- ./gradlew --no-daemon --max-workers=1 :runtime:hosted:nativeFixtureTest --tests '*NativePacketQueryVariantTest' --console=plain
```

The affected V0/V1 selections then passed seven cases with no failures, errors, or skips in 19 seconds:

```sh
mise exec -- ./gradlew --no-daemon --max-workers=1 :runtime:hosted:nativeFixtureTest --tests '*NativePacketQueryTest' --tests '*NativePacketQueryImplementationTest' --console=plain
```

The [new-case log](../evidence/native-query-m1-new-first.log) records successful compilation, native static analysis, and the first six-case run.
The [regression log](../evidence/native-query-m1-prior-regression.log) records the same unchanged inputs and successful gates as up-to-date.
The new XML records six cases at `2026-10-20T03:26:06.212Z`.
The V0 and V1 XML records five and two cases at `2026-10-20T03:26:53.809Z` and `2026-10-20T03:26:45.998Z`.
The first admitted runs of these query selectors passed.
They do not establish integration RED/GREEN pairs.

| Selection | Independently expected behavior | Result |
| --- | --- | --- |
| V0 outgoing | Reach `Transport.send.request[0]` with `WireRequest.destination` and the exact `client.fetch` frame | **PASSED** |
| V0 incoming | Preserve the matched return and reach `Feature.handle` return with `FeatureResponse.link` | **PASSED** |
| V0 controls | Reject invented response-return and request-argument witnesses; preserve exact generated-property identity | **PASSED** |
| V1 outgoing | Preserve the static `BackendClient.fetch` call and concrete `BackendClientImpl.fetch` proof through the same outgoing field route | **PASSED** |
| V1 incoming | Preserve the exact implementation return through the full incoming field route | **PASSED** |
| V1 forbidden witnesses | Emit no response-return witness from the request or request-argument witness from the response | **PASSED** |
| V2 outgoing and incoming | Preserve exact extension receiver entries, helper fields, construction, matched returns, and both forbidden-witness assertions | **PASSED** |
| V3 outgoing and incoming | Preserve the original nested constructor and send seeds through both full routes and both forbidden-witness assertions | **PASSED** |
| V8 outgoing and incoming | Preserve destination argument/formal index 1, send argument index 0, both full routes, and both forbidden-witness assertions | **PASSED** |

All five variants need continuous compiler transfers with exact fields and call contexts.
V1 also needs the exact fixture root, authored provenance, production scope, `main` source set, and `src` directory.
Its kept target proof must contain the implementation relation and exhaustive one-target coverage.
Fresh compiler counters remain evidence, but they do not change call-stack identity.
The oracle uses the existing typed call-stack comparison for path continuity.
Expected declarations, fields, formal indices, helper invocations, and root ownership come from independent fixture inputs.

The outgoing route must end at the exact opaque argument with `EXTERNAL_CALL` and `CompletionUnproven`.
The incoming case accepts `Complete` or a kept `CompletionUnproven` from the existing ledger.
The receipt does not record which incoming completion variant occurred.
The capture preserves that actual qualification and keeps `EvidenceOnly` when completion remains unproven.

Every capture asserted a positive native-flow read count before kept presentation.
All twelve captures kept one path each and added exactly zero native-flow reads during presentation.
The [new-case counters](../evidence/native-query-m1-new-counts.tsv) record initial counts of 16, 8, 10, 12, 18, and 14 in capture order.
The [prior-case counters](../evidence/native-query-m1-prior-counts.tsv) record the six V0/V1 captures with their exact counts.
The XML keeps the original counter lines.
Earlier V0/V1 receipts did not log exact initial counts.
The native read count covers actual adapter reads, without a claim about every compiler operation.
Each forbidden-witness assertion needs nonempty kept compiler evidence.
These assertions inspect emitted witnesses and do not infer absence from `RELATIONSHIP_UNPROVEN`.

The grants remain 256 results, 100,000 work units, 60,000 milliseconds, 4,000,000 returned bytes, and 32,000,000 kept bytes.
The completion rules and one-positive-site request policy remain unchanged.
The shared capture now records bounded read and path counts after its existing semantic assertions.

## Composite prefix and remaining obligations

The exact composite command was:

```sh
mise exec -- ./gradlew --no-daemon --max-workers=1 :relation:intellij:nativeFixtureTest --tests '*NativePacketCompositePathTest'
```

The command ran two cases in 22 seconds and passed its native static-analysis gate.
S1 preserved the original nested constructor and genuine anonymous execution owner.
Its explicit route reached the original client argument, concrete formal, destination conversion, and opaque API body at index 1.
That native prefix passed C1.1–C1.4.
It does not establish complete composite query coverage.

S2 preserved its original qualified execute seed and first receiver transfer.
The next read returned `UNMODELED_CALL` without the expected source-map receiver entry at `289:439`.
That missing transfer establishes the recorded semantic **RED**.
C2.1 callback payload, value-scoped predicates, carrier reconstruction, and C2.2–C2.10 remained **NOT REACHED**.
Their assertions remain **NOT RUN**.

M2 still needs C2.1–C2.2, C2.5–C2.9, and the combined T1 route with exact predicates.
C2.9 reaches the method return payload `Success.value.link`.
C2.8 and C2.9 need both the execute-result subtype and the exact `firstOrNull` non-null predicate.
The source-known empty-list control must exclude a satisfiable feature-link route without backend-code provenance.
Its compiler-backed semantic assertion remains **NOT RUN**.
The standalone runtime control already distinguishes `NO_OFFER` from `BACKEND_DENIED`, but that result does not establish the compiler predicate.

Full `QueryService` assertions for composite C1, C2, C3, and T1 remain **NOT RUN** here.
Reviewed external models, complete error transport, S3 logging, and installed-host capture remain outside this proof.
The parent owns the next shared guard and callback-value contracts.

## Setup failures and immutable controls

Earlier V0 query commands stopped at compilation, static analysis, property lookup, or `MISSING_DECLARATION` setup stages.
Their full-route assertions remained **NOT RUN**.
The [command record](../evidence/native-query-commands.tsv) preserves those outcomes separately from the property identity RED/GREEN pair.
The first detached implementation command compared strings with `CanonicalCompilerType` values and failed one fixture expectation.
The same fifteen cases passed after that expectation compared the exact type values.
That fixture error does not establish product RED.

`FieldStorageControls.kt`, `DefaultIdentityControls.kt`, and `TestEchoMapper.kt` remain separate supplemental sources.
They do not replace original packet seeds.
The [derived manifest](../derived-sources.sha256) remains `df263db1f6ebeff1a64461ec41f32954006d2aeed673c1adc5893426e1a30e8c`.
The [runner manifest](../runner-inputs.sha256) remains `3eea9461f33fc3b3c83bd4c56192e8d61ca7e4375b35392e8855de1d361ad8cf`.

## Latest receipt digests

| Receipt | SHA-256 |
| --- | --- |
| `native-query-m1-new-green.xml` | `13ae0810a47d48a6480a535d00b64cb20db588b08378e75db4c6138104d5129b` |
| `native-query-m1-v0-regression-green.xml` | `81dacbd1666aee9c03c6ba91f9447cea273228b234d466c574e10174d88b9821` |
| `native-query-m1-v1-regression-green.xml` | `aaf1943a679d8eebf1dd8da0e79f2f80bed5a2eef6e06446ddb28e4db2c29512` |
| `native-composite-map-receiver-red.xml` | `8fa5bf0e54e75b46fd8f8871343b2a5486fca7c68d4f981b8fdcf5bcd398891d` |
