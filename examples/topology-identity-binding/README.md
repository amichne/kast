# Topology declaration binding: focused implementation example

Replace the **live-target-versus-registry rendered-signature comparison** with an
independent K2 declaration-binding proof, then reuse the existing registry symbol.
Do not change public selector identity formats to repair this one comparison.

## Status and authority

This is an executable reference policy, a native adapter example, a public test
corpus, and a typed delivery graph. **It is not wired into production Kast.**
The native adapter and conformance hook require compilation and execution against
Kast's matched IDEA/Kotlin plugin. Pure reference tests cannot prove that step.
`verifyDelivery` deliberately rejects production completion, even if somebody
places hand-written PASS receipts in its output directory.

Implementation baseline: `d9b4a16d488780386de63e0345b1163cee029a22` (v0.33.0).
The current checkout, its `AGENTS.md`, architecture policy, and version catalog
remain the authority. An enterprise `compiler-identity-mismatch` was reported;
no enterprise source or trace is required or included. Its trigger is not proven.

The attached public fixture is retained without treating its compilation as proof
of K2 behavior. The task graph follows the requested executable-delivery contract:
explicit dependencies, read/write bounds, commands, proof boundaries and receipts.
Only the reference gate executor is implemented in this example. The remaining
production gate executors are requirements, not fictitious completed tooling.

## Why this is the focused change

Current `TopologyK2Projection.kt` projects declarations in `analyze(declaration)`.
It later projects a resolved target in the reference/override analysis context.
Both paths construct signatures from K2 type `toString()` values; the registry
compares the derived identities at a matched declaration location.

Current upstream `KaFirUsualClassType.toString()` invokes `renderForDebugging()`.
A stable printer would not solve declaration-versus-use-site substitution either.
`directlyOverriddenSymbols` already unwraps substitution/intersection overrides
according to the Analysis API contract. Blindly unwrapping everything again is
not the repair.

Three alternatives were considered:

| Alternative | Decision |
| --- | --- |
| Accept a matching file/range or qualified name | Reject: this does not prove the target declaration. |
| Replace all compiler identities and serialize the full Kotlin type system | Defer: much larger public/persistence change than this join requires. |
| Independently bind the resolved K2 target to the registered declaration | Use: preserve overload selection, current epoch and existing registry identity. |

This narrows the previous broader identity-migration proposal deliberately. The
existing declaration-issued signature remains metadata/identity elsewhere in Kast.
This example does **not** claim to remove every rendered identity in the product.
Do not expand this patch into relations, mutation, or selector migration unless a
focused native proof demonstrates that it is necessary for this repair.

## Required behavior

For each source target already admitted by the topology operation:

1. The registry supplies a candidate entry and its exact admitted declaration location.
2. The existing file-content and epoch checks admit that candidate.
3. Reload the registered declaration **from the registry location**, independently
   of the live target. A copied `target.psi` is not an independent lookup.
4. In the reference's active analysis session, obtain that declaration's symbol.
5. Require matching admitted source roles and native containing modules, then use
   native source-symbol equality to prove that it is the resolved target.
6. Return the exact registry-owned `TopologySymbol` only after proof succeeds.
7. Reject unavailable or contradictory proof. Never substitute string equality,
   type erasure, name lookup, source coordinates alone, or the first candidate.

The native equality behavior is itself a proof obligation on the matched plugin:
if it does not establish identity for a required case, this work stays open.
Use a documented compiler normalization/restoration operation with native tests;
do not introduce fallback acceptance or assert that equality is universally sufficient.

### Concrete example

```kotlin
interface Feed<out T> {
    val state: StateFlow<T>
    fun accepts(value: @UnsafeVariance T): Boolean
}
fun <T> read(feed: Feed<T>): T = feed.state.value
fun readString(feed: Feed<String>): String = feed.state.value
```

Both `state` references must bind to the registered `Feed.state` declaration.
Use-site types may differ. No type rendering enters the binding decision.
`@UnsafeVariance` is left to Kotlin; Kast neither strips it nor interprets variance.
An explicit `StringFeed.state` override remains distinct from `Feed.state`.

The reference API enforces the ownership sequence:

```kotlin
val result = ProvenBinding.bind(entry, currentEpoch, resolvedTarget, compilerAuthority)
when (result) {
    is BindingResult.Complete -> useRegistrySymbol(result.binding.entry)
    is BindingResult.Rejected -> rejectTopology(result.difference)
}
```

`CompilerAuthority` owns the independent lookup by `entry`; the caller cannot pass
an unrelated registered symbol alongside an arbitrary registry identity. Production
construction belongs only inside the IntelliJ adapter. A test compiler is not an
alternative production implementation.

## Exact production delta still required

| Location | Required change |
| --- | --- |
| `TopologyProjectionRegistry.kt` | Expose an unproven candidate entry keyed by existing admitted file/range. Keep the registry detached and generation-bound. Do not let candidate lookup itself return binding proof. |
| `TopologyK2Projection.kt` | Replace the target-side `topologyProjection()` / rendered identity comparison with the native binding flow. Leave declaration issuance and public identity encoding unchanged. Pass the active `KaSession` explicitly or keep the binding inside the existing `analyze` block. |
| `IntellijTopologyFileExtractor.kt` | Implement independent PSI reload using existing content/epoch checks. Consume only proven bindings when emitting edges. Keep cancellation and publication behavior intact. |
| topology failure contract and telemetry | Preserve genuine native binding failures as finite data. Do not force them into evidence constructors that require two unequal rendered hashes: different declarations can have equal renderings. Map the focused failure to the existing public rejection without weakening the protocol. Emit bounded stage/reason evidence; no source or enterprise payloads. |
| existing hosted and installed tests | Invoke `verifyNativeFixture` in the already-imported public fixture. Assert exact targets and edge coverage, not merely successful topology publication. |

No new production Gradle module is required for this first change. Keep the helper
inside `:topology:intellij`. A later cross-surface identity policy is a separate
requirement with its own compatibility proof.

### Scope boundaries

In scope: false mismatches for existing admitted source declarations, including
arbitrary compiler-supported generic/type forms, plus real mismatch rejection.
No type whitelist, annotation stripping, or handwritten Kotlin type normalizer.

Out of scope: TLS/truststores, JBR selection, service managers, new project-opening
paths, public identity-format migration, cross-revision symbol tracking, graph-wide
partial success, and new source-mutation authority.

The native example intentionally preserves the current `SOURCE` origin scope.
It does not use blanket `fakeOverrideOriginal`, and rejects intersection targets
rather than choosing a representative. Generated/delegated/library origins and
new graph node kinds are not silently promoted to source declarations.

**This does not establish support for every Kotlin program.** The focused claim is
that supported source-binding decisions do not depend on type spelling. If a
required public generic/inherited fixture resolves to an origin the adapter cannot
admit, NATIVE_BINDING fails and must be resolved; do not delete that case or mark an
omitted edge complete. Generated-origin coverage needs an explicit separate policy.

## Module, capability and execution graphs

```text
current imported Project + admitted read epoch             [existing workspace authority]
  -> topology IntelliJ adapter                             [compiler-read effect]
     -> independent registry source lookup
     -> K2 binding proof -> detached registry symbol
        -> topology build coordinator -> existing publication authority
```

No `Project`, PSI, `KaSymbol`, or `KaSession` escapes into the detached registry,
contract, wire or SQLite. No session/pointer cache is added. No new backend,
service locator, process launch or project owner is introduced.

The execution transitions are `candidate -> admitted source -> proven binding`
or `candidate -> rejected`. Source/epoch change invalidates evidence and requires
new admission. Recovery is recomputation under a new admitted epoch, not a retry
that accepts an old binding. There is no alternate success lane.

## Run the example

From a checkout containing the repository Gradle wrapper and configured Java 25:

```sh
./gradlew -p examples/topology-identity-binding verifyReference generateProgram verifyFixture verifyReceiptFailures
./gradlew -p examples/topology-identity-binding compileNativeKotlin \
  -PideaHome=/absolute/path/to/IntelliJ\ IDEA.app/Contents
./gradlew -p examples/topology-identity-binding verifyReferenceReceipt
./gradlew -p examples/topology-identity-binding verifyDelivery
```

`compileNativeKotlin` requires the exact reference IDEA/Kotlin plugin from the
repository version catalog. It uses those installed JARs as compile-only inputs,
not a separately downloaded Analysis API. It does not prove native execution.
`verifyDelivery` exits nonzero because production integration and its proofs have
not been implemented here. That is a guard, not a successful completion receipt.

`verifyNativeFixture(file, epoch)` is executable test code, but not a standalone
IDE launcher. Wire it into the existing imported-project harness to obtain native
proof. It independently chooses fixture declarations before resolving references.
It includes eight positive reference cases and a negative explicit-override case.
Add wrong-overload, wrong-module, source-change and fresh-session cases in that
harness before admitting the production change.

A host-neutral smoke check can use an already available Kotlin compiler:

```sh
kotlinc examples/topology-identity-binding/src/main/kotlin/*.kt \
  -include-runtime -d /tmp/identity-reference.jar
java -jar /tmp/identity-reference.jar check
java -jar /tmp/identity-reference.jar graph
```

This proves only the pure reference policy and task graph. It does not replace
Kast's configured compiler, native proof, or installed acceptance.

## Delivery graph and completion

`Program.kt` is the canonical typed task graph. `graph` emits deterministic JSON
including task contracts, calculated dependency waves and the current module edges.
The included schema covers that graph, reference reports and proof receipts.

```text
MODEL -------+                         [reference lane]
NATIVE_API --+-> NATIVE_BINDING -> INTEGRATION -> ARCHITECTURE
                                                   |
                                                INSTALLED -> REPLAY
                                                   -> CLEAN_CHECKOUT -> CI -> REVIEW -> REVALIDATION
```

Only MODEL receipt generation is implemented. NATIVE_API compilation is available;
the remaining gate commands in the graph are **proposed integration tasks** to add
or bind to existing repository gates. They are not asserted to exist on baseline.
Every native/installed node names the missing implementation and its review boundary.

The MODEL receipt runs the actual Java commands, binds the full tracked input tree,
exact git head, program fingerprint, compiler classpath, commands, observed case
names and artifact digests. It refuses dirty checkouts, deletes predecessor success
before a new attempt and rechecks inputs after execution. Receipt verification
rejects altered artifacts, classpaths, programs and revisions. No completion flag
can promote a model receipt to native proof.

To finish the production delivery, implement per-gate receipts with predecessor
receipt digests and exact-head command/artifact bindings. Reuse repository receipt
and installed-harness infrastructure; do not build a second general delivery engine.
CI and independent review require their real external authorities. A self-review
can find defects but cannot satisfy REVIEW.

| Requirement | Implementation / proof | Completion evidence |
| --- | --- | --- |
| No live type presentation in binding | `Binding.kt`, `K2SourceBinding.kt` | MODEL + NATIVE_BINDING |
| Exact registered declaration; overload/module/role separation | independent lookup and native comparison | NATIVE_BINDING + installed negative cases |
| Generics and `@UnsafeVariance` retain correct targets | public fixture and native conformance | NATIVE_BINDING + INSTALLED |
| No silent edge loss | existing installed traversal/relation/source oracle | INSTALLED, with explicit expected edges |
| Epoch and ownership preserved | existing admission + binder epoch gate | REPLAY + ARCHITECTURE |
| No protocol migration or adjacent capability | final source/schema/module diff | ARCHITECTURE + REVIEW |
| Final code is actually proven | detached checkout, exact-head CI, independent review | CLEAN_CHECKOUT + CI + REVIEW + REVALIDATION |

Completion requires every row PASS at the final exact head. Any final edit
invalidates earlier completion evidence. The graph and example compiling are not
substitutes for those proofs.

## Source evidence

- Baseline implementation: `topology/intellij/src/main/kotlin/io/github/amichne/kast/topology/intellij/TopologyK2Projection.kt` and `TopologyProjectionRegistry.kt` at the baseline above.
- Baseline ownership: root `AGENTS.md`, `topology/intellij/build.gradle.kts`, `gradle/libs.versions.toml`.
- Analysis API symbols and session lifetimes: https://kotlin.github.io/analysis-api/symbols.html
- Types and comparison: https://kotlin.github.io/analysis-api/types.html
- Upstream symbol relations: https://github.com/JetBrains/kotlin/blob/2fb4c11fa297d0cea0a80486800321f1a192fb87/analysis/analysis-api/src/org/jetbrains/kotlin/analysis/api/components/KaSymbolRelationProvider.kt
- Upstream debugging renderer: https://github.com/JetBrains/kotlin/blob/2fb4c11fa297d0cea0a80486800321f1a192fb87/analysis/analysis-api-fir/src/org/jetbrains/kotlin/analysis/api/fir/types/KaFirUsualClassType.kt

Upstream source informs the design; the admitted installed plugin owns behavior.
