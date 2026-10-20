# Request and response fixture

This fixture preserves the supplied packet and its corrected oracle.
The five composite Kotlin files match the packet code blocks byte for byte.
V0 adds `package fixture.variants.v0` before its original body.
The original constructor seeds remain inside their original execution owners.

## Run the fixture

From the Kast repository root, run this command:

```sh
./gradlew -p experiments/representation-impact/request-response-fixture assertFixture --console=plain
```

The project uses the repository Gradle wrapper, Kotlin `2.4.21`, JDK `25`, and `-Werror`.
The command compiles the composite, V0–V8, the runtime assertions, and the typed oracle.
It examines all seven source manifests before the assertions.

The runtime assertions compare exact fields for nonempty success, empty success, and backend error.
They also examine request fields, headers, logger arguments, the test echo, a constant mapper, and two independent mapper calls.
These assertions establish fixture behavior.
They do not establish compiler-grounded value paths or native Kast success.

## Source scopes

`src/main/kotlin` contains the composite, isolated variants, and pure controls.
`src/test/kotlin` contains the original `EchoOffersClient` and the derived `TestEchoMapper` control.
Use these two roots for native source-domain assertions.

`runtime/kotlin` contains controlled runtime effects, including an implementation of `OffersApi`.
`oracle` contains the authored expectations and the pure evaluator.
These directories belong to the separate `fixtureRuntime` source set.
Exclude that source set from native queries so its transport implementation does not change the opaque-call fixture.

## Corrected oracle

`oracle/RequestResponseOracle.kt` owns the stable C1, C2, C3, T1, and variant expectations.

`initial-freeze.sha256` freezes that file, the composite, and V0.
`derived-sources.sha256` freezes V1–V8 and the pure controls.
`segment4-controls.sha256` identifies the renamed carrier, constant callback, and user-defined `invoke` controls.
`segment4-return-controls.sha256` identifies the normal, discarded, abrupt, and finally cases for anonymous returns.

`segment6-library-controls.sha256` identifies the source-defined `firstOrNull` control.
`segment5-branch-controls.sha256` identifies normal, bottom-type, Unit, and discarded branch controls.
`segment5-entry-controls.sha256` identifies immutable and reassigned mutable destructuring controls.

The standalone assertion command examines all seven manifests.
`runner-inputs.sha256` records that procedure and its manifest inputs.
`upstream-inputs.sha256` records the supplied packet and audit identities.

The oracle keeps success and error payload routes separate through replacement carriers.
C2.8 and C2.9 both need the success-subtype predicate and the selected-link non-null predicate.
C1.N1 forbids the production implementation route in both source policies.
T1 expects the test echo route only in `PRODUCTION_AND_TEST`.
The production exclusion needs a known candidate and its source-policy rule.
V8 distinguishes constructor parameter `destination` at index 1 from `Transport.send.request` at index 0.

`oracle/OracleEvaluation.kt` accepts recorded witnesses and known exclusions.
A positive row passes only when every expected field route appears with its transfers and predicates.
A forbidden row reports whether a prohibited witness appears among the supplied witnesses.
That result does not prove that the destination cannot occur at runtime.
`RELATIONSHIP_UNPROVEN` does not satisfy a positive row or prove absence.
Query completion stays separate from row outcomes.

## Derived variants

Each variant has its own package and preserves the `ClientRequest` constructor seed in `Feature.handle`.

| Variant | Controlled change |
| --- | --- |
| V1 | One source implementation of the client interface |
| V2 | Request and response extension mappers |
| V3 | Nested constructors and calls without local values |
| V4 | Local carrier values and member `map` calls |
| V5 | V4 plus `sequential`, a nested constructor seed, and destructuring |
| V6 | A collection field and `first()` |
| V7 | A source wrapper around the supplied function value |
| V8 | Named arguments and defaults with distinct formal indices |

## Evidence limits

The runtime controls do not establish static provenance, dispatch coverage, forbidden-witness absence, or query completion.
Native fixtures must use real K2 resolution and the frozen source bytes.
Public capture must preserve its exact source, schema, executable, basis, requests, grants, witnesses, and unresolved obligations.
The source manifests preserve source identity across those separate procedures.
The supplemental `firstOrNull` captures include library declarations.
The original S2 request keeps `SymbolLibraryPolicy.EXCLUDE`.
