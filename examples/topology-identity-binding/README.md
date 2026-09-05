# Topology declaration binding

The reference implementation from `examples/topology-declaration-binding` is integrated into
`:topology:intellij`. The standalone policy implementation and speculative delivery graph have
been removed. The executable public fixture lives in `fixtures/topology-identity-workspace`.

## Production behavior

`TopologyProjectionRegistry` issues an unproven candidate for an exact admitted file and
declaration range. The candidate retains its registry generation and registry-owned symbol.
`IntellijTopologyFileExtractor` independently reloads that file, checks its admitted content hash
and document readiness, then locates the exact declaration range. Admitted PSI is reused only
inside the current uninterrupted read action.

`ProvenTopologyBinding` obtains the declaration symbol in the reference's active `KaSession`.
It checks the candidate generation, admitted source origins, registry/declaration/target roles,
containing modules and native symbol equality. Only its private constructor can return a proven
binding to the registry-owned symbol. The live target is never re-rendered or assigned a new
compiler identity for this join.

Inherited `SUBSTITUTION_OVERRIDE` targets use K2's documented `fakeOverrideOriginal` operation
before source admission, and require one distinct source declaration from `directlyOverriddenSymbols`
that agrees with that normalized target. This preserves intersection multiplicity and excludes
delegated originals. It is needed for `StringFeedView.state` and `StringFeedView.accepts`.
Explicit source overrides retain their own identity. Other origins, including direct intersection
and delegated targets, retain the existing unsupported-origin policy. This is not a claim of
complete topology support for every Kotlin program.

Native binding failures carry a finite reason, stage, source/target location and cache disposition.
They retain the existing public `compiler-identity-mismatch` rejection. Telemetry no longer
requires unequal rendered hashes or exports signatures. Target file-load failures retain their
original typed failures, including the existing bounded VFS refresh/retry behavior. Dirty-document and uncommitted-PSI
failures are not cached, so recovery is observable without a content-generation change.

## Verification

```sh
./gradlew :topology:intellij:check :topology:contract:check :topology:build:check :runtime:telemetry:check
./gradlew topologyDeclarationBindingAcceptance
./gradlew build verifyKastArchitecture
python3 -m unittest discover -s integration-tests -p 'test_topology*.py'
```

The installed acceptance task uses the existing isolated runtime harness and the configured
matched IDEA/Kotlin plugin. Set `-PkastAcceptanceIdeaHome=/absolute/IDEA/home` when needed.
It is a prerequisite of `releaseSourceGate`, which CI already executes.

The oracle independently identifies exact declarations, then checks directed topology edges and
source occurrences for generic, star-projected, aliased, inherited, explicit override, overload
and nested-binder cases. A separate Gradle module deliberately repeats compiler identities.
Negative checks reject wrong overload, base-override and cross-module edges, and nested
substitution over delegation or intersection. After changing source,
the gate requires rejection of the old selector, rebuilds topology and repeats the oracle.
Reports are written to `build/reports/topology-binding/acceptance.json`.

The baseline public fixture published without reproducing the reported enterprise rendered-type
mismatch. The tightened role test demonstrated a separate unsafe acceptance in the old registry,
and the exact installed oracle exposed the missing inherited reference. Those observations must
not be presented as reproduction of the unavailable enterprise trace. Direct fault injection of
native module/equality failures is not part of the installed public-command oracle.

The compiler API contract for inherited substitution restoration is documented in
[KaSymbolRelationProvider](https://github.com/JetBrains/kotlin/blob/2fb4c11fa297d0cea0a80486800321f1a192fb87/analysis/analysis-api/src/org/jetbrains/kotlin/analysis/api/components/KaSymbolRelationProvider.kt).
The installed plugin and executed acceptance tests own the runtime behavior.
