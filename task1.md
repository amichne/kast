## Task 1 — Preserve exact symbol discovery qualifications

**Goal**

Preserve the complete domain qualification set through the public `symbol.discover` protocol instead of collapsing all non-result-limit states into `EVIDENCE_INCOMPLETE`.

**Baseline**

`amichne/kast@d0f94a985cb023f9069e06b6313afded7e31529b`

**Why**

The domain already represents discovery limitations as a non-empty set including work, time, byte, result, provider, dumb-mode, unsupported-item, and exact-definition limitations. The canonical handler currently reduces that proof to either `RESULT_LIMIT` or `EVIDENCE_INCOMPLETE`.

This violates the existing requirement that established qualification survives the boundary.

**Allowed writes**

```text
protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalReadOperationModels.kt
protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/CanonicalReadSerializers.kt
protocol/wire/src/test/**

runtime/composition/src/main/kotlin/io/github/amichne/kast/runtime/composition/protocol/CanonicalSymbolHandlers.kt
runtime/composition/src/test/**
```

**Implementation**

Replace the public catch-all enum with a structured qualification:

```kotlin
enum class SymbolDiscoverLimitation {
    RESULT_LIMIT,
    BYTE_LIMIT,
    WORK_LIMIT,
    TIME_LIMIT,
    DUMB_MODE_TRANSITION,
    PROVIDER_FAILURE,
    UNSCOPED_PROVIDER,
    UNSUPPORTED_ITEM,
    EXACT_DEFINITION_UNAVAILABLE,
}

class SymbolDiscoverQualification private constructor(
    val limitations: List<SymbolDiscoverLimitation>,
) : OperationQualification {
    companion object {
        fun from(
            raw: Set<SymbolDiscoverLimitation>,
        ): Refinement<
            SymbolDiscoverQualification,
            SymbolDiscoverQualificationFailure,
        > {
            val canonical = raw.distinct().sorted()
            return if (canonical.isEmpty()) {
                Refinement.Rejected(SymbolDiscoverQualificationFailure.EMPTY)
            } else {
                Refinement.Refined(SymbolDiscoverQualification(canonical))
            }
        }
    }
}
```

Map every `symbol.contract.SymbolDiscoveryQualification` exhaustively into its protocol equivalent.

Do not select a primary limitation.

The wire must preserve the complete ordered set:

```json
{
  "status": "qualified",
  "candidateSelectors": [],
  "qualification": {
    "limitations": ["work-limit"]
  }
}
```

A multi-limitation outcome must retain every limitation in deterministic order.

Also change provider `RuntimeException` handling in `IntellijNativeDiscoveryQuery` so the provider implementation class and stack trace are written through IntelliJ logging. Public output remains only `PROVIDER_FAILURE`.

**RED**

```shell
./gradlew \
  :runtime:composition:test \
  :protocol:wire:test \
  --tests '*SymbolDiscover*'
```

Add failing tests proving:

```text
WORK_LIMIT_REACHED -> currently becomes evidence-incomplete
PROVIDER_FAILURE   -> currently becomes evidence-incomplete
multiple limitations -> currently cannot round-trip completely
```

**GREEN**

```text
Every domain discovery qualification has one public limitation.
No qualification is collapsed into a generic incomplete state.
Multiple limitations survive round-trip serialization.
Ordering is deterministic.
Provider exceptions retain local diagnostic detail.
```

**Non-goals**

```text
Do not change symbol discovery execution.
Do not change work limits.
Do not change selector issuance.
Do not change OperationOutcome or kernel types.
Do not expose exception text publicly.
```

**Done when**

Every established discovery limitation survives domain → composition → wire without semantic loss.

