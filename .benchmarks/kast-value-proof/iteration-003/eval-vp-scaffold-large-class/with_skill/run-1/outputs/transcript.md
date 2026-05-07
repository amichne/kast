# Value-proof transcript: vp-scaffold-large-class

## Prompt
Summarize the public API of EvaluationDiagnostics — list every public type, interface, enum, and data class it contains, with their member signatures.

## Timing
- Start epoch seconds: 1778120792
- End epoch seconds: 1778120999

## Tool calls and key results

1. `skill("kast")`
   - Loaded the Kast skill context.

2. `bash("date +%s")`
   - Key result: `1778120792`.

3. `kast_resolve(workspaceRoot="/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-with", symbol="EvaluationDiagnostics", kind="class")`
   - Key result: failed with `NO_BACKEND_AVAILABLE`; message instructed starting `kast daemon start --workspace-root=/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-with`.

4. `bash("kast daemon start --workspace-root=/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-with >/dev/null && echo started")`
   - Key result: exited 143 after daemon startup attempt; backend was not yet available.

5. `kast_resolve(workspaceRoot="/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-with", symbol="EvaluationDiagnostics", kind="class")`
   - Key result: failed again with `NO_BACKEND_AVAILABLE`.

6. `bash(mode="async", detach=true, "kast daemon start --workspace-root=/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-with")`
   - Key result: detached backend start command launched.

7. `kast_resolve(workspaceRoot="/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-with", symbol="EvaluationDiagnostics", kind="class")`
   - Key result: resolved `io.amichne.konditional.internal.evaluation.EvaluationDiagnostics`.
   - File: `/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-with/konditional-engine/src/main/kotlin/io/amichne/konditional/internal/evaluation/EvaluationDiagnostics.kt`.
   - Declaration preview: `data class EvaluationDiagnostics<T : Any>(` at line 37.

8. `kast_scaffold(workspaceRoot="/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-with", targetFile="/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-with/konditional-engine/src/main/kotlin/io/amichne/konditional/internal/evaluation/EvaluationDiagnostics.kt", targetSymbol="EvaluationDiagnostics", mode="summary")`
   - Key result: failed with `CLI_INTERNAL_ERROR`; `WrapperScaffoldMode` did not contain `summary`.

9. `kast_scaffold(workspaceRoot="/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-with", targetFile="/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-with/konditional-engine/src/main/kotlin/io/amichne/konditional/internal/evaluation/EvaluationDiagnostics.kt", targetSymbol="EvaluationDiagnostics")`
   - Key result: failed with `INTERNAL_ERROR`; scaffold wrapper sent unknown key `includeUsageSiteScope`.

10. `kast_scaffold(workspaceRoot="/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-with", targetFile="/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-with/konditional-engine/src/main/kotlin/io/amichne/konditional/internal/evaluation/EvaluationDiagnostics.kt", targetSymbol="EvaluationDiagnostics", mode="implement")`
   - Key result: failed with the same unknown key `includeUsageSiteScope`.

11. `bash("kast scaffold ... --json")`
   - Key result: failed with `CLI_USAGE`; this CLI does not expose a direct `scaffold` command in this build.

12. `bash("kast help outline && kast help resolve | head -120")`
   - Key result: confirmed CLI supports `outline` and position-based `resolve --include-body=true --include-documentation=true`.

13. `bash("kast outline --workspace-root=... --file-path=...")`
   - Key result: returned a large declaration outline for `EvaluationDiagnostics.kt`.

14. `bash("kast resolve --workspace-root=... --file-path=... --offset=1456 --include-body=true --include-documentation=true")`
   - Key result: returned the full declaration scope for `EvaluationDiagnostics`, including all nested public declarations used for the summary below.

15. `bash("date/write transcript/timing/verify")`
   - Key result: wrote this transcript and `timing.json`, then verified both are non-empty.

## Final structured public API summary

### `data class EvaluationDiagnostics<T : Any>`
Signature:
```kotlin
data class EvaluationDiagnostics<T : Any>(
    val namespaceId: String,
    val featureKey: String,
    val configVersion: String?,
    val mode: Metrics.Evaluation.EvaluationMode,
    val durationNanos: Long,
    val value: T,
    val decision: Decision,
)
```

### `sealed interface EvaluationDiagnostics.Decision`
Signature:
```kotlin
sealed interface Decision
```
Contained public members:
```kotlin
data object RegistryDisabled : Decision
data object Inactive : Decision

data class Rule(
    val matched: RuleMatch<RuleExplanation>,
    val skippedByRollout: RuleMatch<RuleExplanation>? = null,
) : Decision

data class Default(
    val skippedByRollout: RuleMatch<RuleExplanation>? = null,
) : Decision
```

### `data class EvaluationDiagnostics.RuleMatch<out D : RuleDetails>`
Signature:
```kotlin
data class RuleMatch<out D : RuleDetails>(
    val rule: D,
    val bucket: BucketInfo,
) : RuleDetails by rule
```

### `sealed interface EvaluationDiagnostics.RuleDetails`
Signature:
```kotlin
sealed interface RuleDetails {
    val note: String?
    val rollout: RampUp
    val locales: Set<String>
    val platforms: Set<String>
    val versionRange: VersionRange
    val axes: Map<String, Set<String>>
    val baseSpecificity: Int
    val extensionSpecificity: Int
    val totalSpecificity: Int
    val extensionClassName: String?
    val ruleId: String
    val extensionNode: ExtensionNode
    val conditionalContextNode: ConditionalContextNode
}
```

### `enum class EvaluationDiagnostics.ExtensionType`
Signature:
```kotlin
enum class ExtensionType {
    NONE,
    LAMBDA,
}
```

### `enum class EvaluationDiagnostics.ConditionalContextType`
Signature:
```kotlin
enum class ConditionalContextType {
    NONE,
    NARROWING,
}
```

### `sealed interface EvaluationDiagnostics.TargetingNode`
Signature:
```kotlin
sealed interface TargetingNode
```
Contained public members:
```kotlin
data class All(
    val children: List<TargetingNode>,
) : TargetingNode

data class AnyOf(
    val children: List<TargetingNode>,
) : TargetingNode

data class Locale(
    val ids: Set<String>,
) : TargetingNode

data class Platform(
    val ids: Set<String>,
) : TargetingNode

data class Version(
    val range: VersionRange,
) : TargetingNode

data class Axis(
    val axisId: String,
    val allowedIds: Set<String>,
) : TargetingNode

data object Custom : TargetingNode

data class Guarded(
    val child: TargetingNode,
) : TargetingNode
```

### `data class EvaluationDiagnostics.ExtensionNode`
Signature:
```kotlin
data class ExtensionNode(
    val type: ExtensionType,
    val content: TargetingNode? = null,
)
```

### `data class EvaluationDiagnostics.ConditionalContextNode`
Signature:
```kotlin
data class ConditionalContextNode(
    val type: ConditionalContextType,
    val content: TargetingNode? = null,
)
```

### `data class EvaluationDiagnostics.RuleExplanation`
Signature:
```kotlin
data class RuleExplanation(
    override val note: String?,
    override val rollout: RampUp,
    override val locales: Set<String>,
    override val platforms: Set<String>,
    override val versionRange: VersionRange,
    override val axes: Map<String, Set<String>>,
    override val baseSpecificity: Int,
    override val extensionSpecificity: Int,
    override val totalSpecificity: Int,
    override val extensionClassName: String?,
    override val ruleId: String,
    override val extensionNode: ExtensionNode = ExtensionNode(ExtensionType.NONE),
    override val conditionalContextNode: ConditionalContextNode =
        ConditionalContextNode(ConditionalContextType.NONE),
) : RuleDetails
```
