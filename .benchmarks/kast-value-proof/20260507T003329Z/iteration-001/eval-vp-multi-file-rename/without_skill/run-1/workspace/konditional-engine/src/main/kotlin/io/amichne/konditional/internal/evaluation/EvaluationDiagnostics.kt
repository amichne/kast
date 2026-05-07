@file:OptIn(KonditionalInternalApi::class)

package io.amichne.konditional.internal.evaluation

import io.amichne.konditional.api.BucketInfo
import io.amichne.konditional.api.KonditionalInternalApi
import io.amichne.konditional.context.RampUp
import io.amichne.konditional.core.ops.Metrics
import io.amichne.konditional.rules.versions.VersionRange

/**
 * Internal diagnostics snapshot for a single evaluation.
 *
 * Invariants:
 * - Captures the outcome from exactly one deterministic evaluation
 *   against one immutable registry snapshot.
 * - Contains only trusted, fully computed data produced by core evaluation;
 *   no parsing or lazy rule inspection occurs here.
 * - `duration` reflects the measured evaluation wall-clock duration
 *   for observability only and does not affect semantics.
 *
 * Boundary expectations:
 * - The snapshot is produced by Konditional internals after rule resolution
 *   and bucketing are complete.
 * - Consumers may safely read this model from internal APIs and observability
 *   integrations without additional casting of rule details beyond the
 *   [`Decision`] branch they are handling.
 *
 * Error semantics:
 * - Construction does not perform recovery or validation.
 *   Invalid intermediate state is prevented by the evaluation pipeline.
 *
 * Performance notes:
 * - This model is immutable and allocation-only.
 *   It is intended to be cheap to copy and safe to share across observers.
 */
data class EvaluationDiagnostics<T : Any>(
    val namespaceId: String,
    val featureKey: String,
    val configVersion: String?,
    val mode: Metrics.Evaluation.EvaluationMode,
    val durationNanos: Long,
    val value: T,
    val decision: Decision,
) {
    /**
     * Closed set of deterministic evaluation outcomes.
     *
     * Invariants:
     * - Exactly one branch describes the final evaluation path.
     * - Rule-specific diagnostics are present only for [`Rule`] and rollout-skipped [`Default`] decisions.
     *
     * Performance notes:
     * - Branch inspection is allocation-free after the enclosing snapshot is created.
     */
    sealed interface Decision {
        /**
         * Indicates evaluation returned the feature default because the namespace registry was globally disabled.
         *
         * Guarantees:
         * - No rule matching or bucketing contributed to the returned value.
         */
        data object RegistryDisabled : Decision

        /**
         * Indicates evaluation returned the feature default because the flag definition was inactive.
         *
         * Guarantees:
         * - No rule matching or bucketing contributed to the returned value.
         */
        data object Inactive : Decision

        /**
         * Indicates evaluation resolved a rule-backed value.
         *
         * Invariants:
         * - [matched] contains the winning rule explanation and its computed bucket metadata.
         * - [skippedByRollout], when present, identifies the first matching rule excluded by ramp-up before the winner.
         */
        data class Rule(
            val matched: RuleMatch<RuleExplanation>,
            val skippedByRollout: RuleMatch<RuleExplanation>? = null,
        ) : Decision

        /**
         * Indicates evaluation returned the flag default because no eligible rule produced a value.
         *
         * Invariants:
         * - [skippedByRollout], when present, identifies the first rule
         *   that matched criteria but was excluded by ramp-up.
         */
        data class Default(
            val skippedByRollout: RuleMatch<RuleExplanation>? = null,
        ) : Decision
    }

    /**
     * Typed rule-match diagnostics paired with bucket metadata.
     *
     * `RuleMatch` is generic so consumers can work with the concrete rule details type directly while still sharing a
     * common match wrapper. The wrapped rule data is also exposed on this type via interface delegation, allowing
     * callers to read properties such as [note] or [totalSpecificity] without navigating through [rule].
     *
     * Invariants:
     * - [rule] and [bucket] describe the same evaluation attempt.
     * - [bucket] is always the computed deterministic bucket used for the associated rule decision.
     *
     * Performance notes:
     * - Delegation avoids copying rule details; the wrapper only stores the typed rule payload and bucket metadata.
     */
    data class RuleMatch<out D : RuleDetails>(
        val rule: D,
        val bucket: BucketInfo,
    ) : RuleDetails by rule

    /**
     * Contract for typed rule details exposed through [`RuleMatch`].
     *
     * Invariants:
     * - Implementations must be immutable and fully derived from trusted evaluation definitions.
     * - Property values must be deterministic for a fixed rule definition.
     *
     * Performance notes:
     * - The interface is property-only so callers can inspect rule details without additional computation.
     */
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

    /**
     * Classifies whether rule targeting included a custom extension predicate.
     */
    enum class ExtensionType {
        NONE,
        LAMBDA,
    }

    /**
     * Classifies whether rule targeting narrowed the context through guarded predicates.
     */
    enum class ConditionalContextType {
        NONE,
        NARROWING,
    }

    /**
     * Deterministic structural view of rule targeting criteria.
     *
     * Guarantees:
     * - Nodes preserve evaluation-relevant targeting structure without executing predicates.
     * - Collections and child ordering must remain stable for a fixed rule definition.
     */
    sealed interface TargetingNode {
        /**
         * Conjunction of child targeting predicates.
         */
        data class All(
            val children: List<TargetingNode>,
        ) : TargetingNode

        /**
         * Disjunction of child targeting predicates.
         */
        data class AnyOf(
            val children: List<TargetingNode>,
        ) : TargetingNode

        /**
         * Locale filter with the accepted locale identifiers.
         */
        data class Locale(
            val ids: Set<String>,
        ) : TargetingNode

        /**
         * Platform filter with the accepted platform identifiers.
         */
        data class Platform(
            val ids: Set<String>,
        ) : TargetingNode

        /**
         * Version-range filter.
         */
        data class Version(
            val range: VersionRange,
        ) : TargetingNode

        /**
         * Axis filter keyed by axis identifier.
         */
        data class Axis(
            val axisId: String,
            val allowedIds: Set<String>,
        ) : TargetingNode

        /**
         * Marker node for custom extension predicates whose internals are intentionally opaque.
         */
        data object Custom : TargetingNode

        /**
         * Wrapper for targeting evaluated after a conditional-context narrowing step.
         */
        data class Guarded(
            val child: TargetingNode,
        ) : TargetingNode
    }

    /**
     * Diagnostics node describing extension predicate participation for a rule.
     */
    data class ExtensionNode(
        val type: ExtensionType,
        val content: TargetingNode? = null,
    )

    /**
     * Diagnostics node describing conditional-context narrowing participation for a rule.
     */
    data class ConditionalContextNode(
        val type: ConditionalContextType,
        val content: TargetingNode? = null,
    )

    /**
     * Deterministic explanation of a rule definition as seen by diagnostics consumers.
     *
     * Guarantees:
     * - Captures only trusted rule metadata derived from the Kotlin rule model.
     * - Does not evaluate custom predicates; opaque extensions are represented structurally through [extensionNode].
     * - [ruleId] is a deterministic identifier derived from namespace, feature key, and stable rule ordinal.
     *
     * Performance notes:
     * - All fields are eagerly materialized when diagnostics are created to keep downstream consumers allocation-light.
     */
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
}
