@file:OptIn(KonditionalInternalApi::class)

package io.amichne.konditional.api

import io.amichne.konditional.core.ops.Metrics
import io.amichne.konditional.core.registry.NamespaceRegistry
import io.amichne.konditional.internal.evaluation.EvaluationDiagnostics
import kotlin.time.Duration

internal fun <T : Any> logExplainIfNeeded(
    result: EvaluationDiagnostics<T>,
    registry: NamespaceRegistry,
    mode: Metrics.Evaluation.EvaluationMode,
) {
    if (mode == Metrics.Evaluation.EvaluationMode.EXPLAIN) {
        registry.hooks.logger.debug {
            "konditional.explain namespaceId=${result.namespaceId} " +
                "key=${result.featureKey} decision=${result.decision::class.simpleName} " +
                "version=${result.configVersion}"
        }
    }
}

internal fun <T : Any> recordEvaluationMetrics(
    result: EvaluationDiagnostics<T>,
    registry: NamespaceRegistry,
    mode: Metrics.Evaluation.EvaluationMode,
    duration: Duration,
) {
    registry.hooks.metrics.recordEvaluation(
        Metrics.Evaluation(
            namespaceId = registry.namespaceId,
            featureKey = result.featureKey,
            mode = mode,
            duration = duration,
            decision = result.decision.toMetricsDecisionKind(),
            configVersion = result.configVersion,
            bucket = result.decision.toMetricsBucket(),
            matchedRuleSpecificity = result.decision.toMatchedRuleSpecificity(),
        ),
    )
}

private fun EvaluationDiagnostics.Decision.toMetricsDecisionKind(): Metrics.Evaluation.DecisionKind =
    when (this) {
        is EvaluationDiagnostics.Decision.RegistryDisabled -> Metrics.Evaluation.DecisionKind.REGISTRY_DISABLED
        is EvaluationDiagnostics.Decision.Inactive -> Metrics.Evaluation.DecisionKind.INACTIVE
        is EvaluationDiagnostics.Decision.Rule -> Metrics.Evaluation.DecisionKind.RULE
        is EvaluationDiagnostics.Decision.Default -> Metrics.Evaluation.DecisionKind.DEFAULT
    }

private fun EvaluationDiagnostics.Decision.toMetricsBucket(): Int? =
    when (this) {
        is EvaluationDiagnostics.Decision.Rule -> matched.bucket.bucket
        is EvaluationDiagnostics.Decision.Default -> skippedByRollout?.bucket?.bucket
        is EvaluationDiagnostics.Decision.RegistryDisabled -> null
        is EvaluationDiagnostics.Decision.Inactive -> null
    }

private fun EvaluationDiagnostics.Decision.toMatchedRuleSpecificity(): Int? =
    when (this) {
        is EvaluationDiagnostics.Decision.Rule -> matched.totalSpecificity
        is EvaluationDiagnostics.Decision.Default -> null
        is EvaluationDiagnostics.Decision.RegistryDisabled -> null
        is EvaluationDiagnostics.Decision.Inactive -> null
    }
