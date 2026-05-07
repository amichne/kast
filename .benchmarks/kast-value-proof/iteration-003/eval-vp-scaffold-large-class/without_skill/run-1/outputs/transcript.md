# EvaluationDiagnostics public API summary

Workspace: `/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-without`
Located file with glob: `konditional-engine/src/main/kotlin/io/amichne/konditional/internal/evaluation/EvaluationDiagnostics.kt`.
Viewed the entire source file and manually extracted the public API below.

## `data class EvaluationDiagnostics<T : Any>`

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

## Nested public API

### `sealed interface Decision`

Signature:

```kotlin
sealed interface Decision
```

Members:

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

### `data class RuleMatch<out D : RuleDetails>`

Signature:

```kotlin
data class RuleMatch<out D : RuleDetails>(
    val rule: D,
    val bucket: BucketInfo,
) : RuleDetails by rule
```

### `sealed interface RuleDetails`

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

### `enum class ExtensionType`

Signature and entries:

```kotlin
enum class ExtensionType {
    NONE,
    LAMBDA,
}
```

### `enum class ConditionalContextType`

Signature and entries:

```kotlin
enum class ConditionalContextType {
    NONE,
    NARROWING,
}
```

### `sealed interface TargetingNode`

Signature:

```kotlin
sealed interface TargetingNode
```

Members:

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

### `data class ExtensionNode`

Signature:

```kotlin
data class ExtensionNode(
    val type: ExtensionType,
    val content: TargetingNode? = null,
)
```

### `data class ConditionalContextNode`

Signature:

```kotlin
data class ConditionalContextNode(
    val type: ConditionalContextType,
    val content: TargetingNode? = null,
)
```

### `data class RuleExplanation`

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
