package io.amichne.konditional.core.ops

import kotlin.time.Duration

object Metrics {
    @ConsistentCopyVisibility
    data class Evaluation internal constructor(
        val namespaceId: String,
        val featureKey: String,
        val mode: EvaluationMode,
        val duration: Duration,
        val decision: DecisionKind,
        val configVersion: String? = null,
        val bucket: Int? = null,
        val matchedRuleSpecificity: Int? = null,
    ) {
        enum class EvaluationMode { NORMAL, EXPLAIN, SHADOW }

        enum class DecisionKind { DEFAULT, RULE, INACTIVE, REGISTRY_DISABLED }
    }

    @ConsistentCopyVisibility
    data class ConfigLoadMetric internal constructor(
        val namespaceId: String,
        val featureCount: Int,
        val version: String? = null,
    ) {
        companion object {
            fun of(namespaceId: String, featureCount: Int, version: String?): ConfigLoadMetric =
                ConfigLoadMetric(namespaceId, featureCount, version)
        }
    }

    @ConsistentCopyVisibility
    data class ConfigRollbackMetric internal constructor(
        val namespaceId: String,
        val steps: Int,
        val success: Boolean,
        val version: String? = null,
    ) {
        companion object {
            fun of(namespaceId: String, steps: Int, success: Boolean, version: String?): ConfigRollbackMetric =
                ConfigRollbackMetric(namespaceId, steps, success, version)
        }
    }
}
