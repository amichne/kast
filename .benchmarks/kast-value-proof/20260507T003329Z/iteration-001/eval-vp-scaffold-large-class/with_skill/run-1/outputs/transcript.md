# Transcript

## Prompt
Summarize the public API of EvaluationDiagnostics — list every public type, interface, enum, and data class it contains, with their member signatures.

## Target workspace used
`/Users/amichne/code/konditional`

## Major tool calls / steps
- Used `kast_resolve` to pin `EvaluationDiagnostics` to its declaration file.
- Used `kast_scaffold` on that file/type to obtain the semantic outline and full file content skeleton.
- Extracted the public nested types and member signatures from the scaffold output.

## Key findings
- The public surface is one outer data class plus 9 nested public types/interfaces/enums/data classes.
- The scaffold output provided enough structure to enumerate all public constructor/member signatures without reading the Kotlin source directly.

## Final answer
Public API summary for `io.amichne.konditional.internal.evaluation.EvaluationDiagnostics` (`konditional-engine/src/main/kotlin/io/amichne/konditional/internal/evaluation/EvaluationDiagnostics.kt`):

- `data class EvaluationDiagnostics<T : Any>(namespaceId: String, featureKey: String, configVersion: String?, mode: Metrics.Evaluation.EvaluationMode, durationNanos: Long, value: T, decision: Decision)`
- `sealed interface Decision`
  - `data object RegistryDisabled : Decision`
  - `data object Inactive : Decision`
  - `data class Rule(matched: RuleMatch<RuleExplanation>, skippedByRollout: RuleMatch<RuleExplanation>? = null) : Decision`
  - `data class Default(skippedByRollout: RuleMatch<RuleExplanation>? = null) : Decision`
- `data class RuleMatch<out D : RuleDetails>(rule: D, bucket: BucketInfo) : RuleDetails`
- `sealed interface RuleDetails` with properties:
  - `note: String?`
  - `rollout: RampUp`
  - `locales: Set<String>`
  - `platforms: Set<String>`
  - `versionRange: VersionRange`
  - `axes: Map<String, Set<String>>`
  - `baseSpecificity: Int`
  - `extensionSpecificity: Int`
  - `totalSpecificity: Int`
  - `extensionClassName: String?`
  - `ruleId: String`
  - `extensionNode: ExtensionNode`
  - `conditionalContextNode: ConditionalContextNode`
- `enum class ExtensionType { NONE, LAMBDA }`
- `enum class ConditionalContextType { NONE, NARROWING }`
- `sealed interface TargetingNode`
  - `data class All(children: List<TargetingNode>) : TargetingNode`
  - `data class AnyOf(children: List<TargetingNode>) : TargetingNode`
  - `data class Locale(ids: Set<String>) : TargetingNode`
  - `data class Platform(ids: Set<String>) : TargetingNode`
  - `data class Version(range: VersionRange) : TargetingNode`
  - `data class Axis(axisId: String, allowedIds: Set<String>) : TargetingNode`
  - `data object Custom : TargetingNode`
  - `data class Guarded(child: TargetingNode) : TargetingNode`
- `data class ExtensionNode(type: ExtensionType, content: TargetingNode? = null)`
- `data class ConditionalContextNode(type: ConditionalContextType, content: TargetingNode? = null)`
- `data class RuleExplanation(note: String?, rollout: RampUp, locales: Set<String>, platforms: Set<String>, versionRange: VersionRange, axes: Map<String, Set<String>>, baseSpecificity: Int, extensionSpecificity: Int, totalSpecificity: Int, extensionClassName: String?, ruleId: String, extensionNode: ExtensionNode = ExtensionNode(ExtensionType.NONE), conditionalContextNode: ConditionalContextNode = ConditionalContextNode(ConditionalContextType.NONE)) : RuleDetails`
