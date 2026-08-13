package io.github.amichne.kast.idea.backend.workspace

import io.github.amichne.kast.api.contract.skill.KastNativeReadCompleteness
import io.github.amichne.kast.api.contract.skill.KastNativeReadQualification
import io.github.amichne.kast.api.contract.skill.KastNativeReadStage
import io.github.amichne.kast.api.contract.skill.KastNativeReadStages
import io.github.amichne.kast.api.contract.skill.KastNativeReadWork
import io.github.amichne.kast.api.contract.skill.KastReadEvidence
import io.github.amichne.kast.api.contract.skill.KastReadStageObservation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.intellij.IntellijFastSymbolReadResult

internal fun IntellijFastSymbolReadResult.Completed<DetachedKastSymbol>.toEvidence(
    generation: Long,
    admissionNanoseconds: Long,
): KastReadEvidence.NativeIntellij {
    val discoveryBatch = when (val outcome = discovery) {
        is SymbolDiscoveryOutcome.Complete -> outcome.batch
        is SymbolDiscoveryOutcome.Qualified -> outcome.batch
    }
    val qualifications = buildSet {
        val outcome = discovery
        if (outcome is SymbolDiscoveryOutcome.Qualified) {
            addAll(outcome.qualifications.values.map(SymbolDiscoveryQualification::toApi))
        }
        addAll(extraQualifications.map(SymbolDiscoveryQualification::toApi))
    }
    return KastReadEvidence.NativeIntellij(
        generation = generation,
        completeness = if (qualifications.isEmpty()) {
            KastNativeReadCompleteness.EXACT
        } else {
            KastNativeReadCompleteness.QUALIFIED
        },
        qualifications = qualifications,
        stages = KastNativeReadStages(
            values = mapOf(
                KastNativeReadStage.ADMISSION_QUEUE to
                    KastReadStageObservation.Measured(admissionNanoseconds),
                KastNativeReadStage.SMART_MODE_OR_TRANSITION_WAIT to
                    KastReadStageObservation.NotApplicable,
                KastNativeReadStage.SEARCH_SCOPE_COMPILATION to
                    KastReadStageObservation.Measured(metrics.scopeCompilationNanoseconds),
                KastNativeReadStage.NATIVE_QUERY to
                    KastReadStageObservation.Measured(
                        discoveryBatch.timings.nativeQuery.value,
                    ),
                KastNativeReadStage.SEMANTIC_RESOLUTION to
                    KastReadStageObservation.Measured(metrics.semanticResolutionNanoseconds),
                KastNativeReadStage.PERSISTENCE_OR_PUBLICATION to
                    KastReadStageObservation.NotApplicable,
                KastNativeReadStage.PROJECTION_SERIALIZATION to
                    KastReadStageObservation.Measured(
                        discoveryBatch.timings.projection.value +
                        metrics.definitionProjectionNanoseconds,
                    ),
                KastNativeReadStage.IPC to KastReadStageObservation.OutsideResponseBoundary,
            ),
        ),
        work = KastNativeReadWork(
            vfsRefreshCount = 0L,
            gradleImportCount = 0L,
            graphBuildCount = 0L,
            sqliteWriteCount = 0L,
            readActionCount = 1L,
        ),
        projectionBytes = metrics.projectionBytes.value,
    )
}

private fun SymbolDiscoveryQualification.toApi(): KastNativeReadQualification = when (this) {
    SymbolDiscoveryQualification.RESULT_LIMIT_REACHED ->
        KastNativeReadQualification.RESULT_LIMIT_REACHED
    SymbolDiscoveryQualification.BYTE_LIMIT_REACHED ->
        KastNativeReadQualification.BYTE_LIMIT_REACHED
    SymbolDiscoveryQualification.WORK_LIMIT_REACHED ->
        KastNativeReadQualification.WORK_LIMIT_REACHED
    SymbolDiscoveryQualification.TIME_LIMIT_REACHED ->
        KastNativeReadQualification.TIME_LIMIT_REACHED
    SymbolDiscoveryQualification.DUMB_MODE_TRANSITION ->
        KastNativeReadQualification.DUMB_MODE_TRANSITION
    SymbolDiscoveryQualification.PROVIDER_FAILURE ->
        KastNativeReadQualification.PROVIDER_FAILURE
    SymbolDiscoveryQualification.UNSCOPED_PROVIDER ->
        KastNativeReadQualification.UNSCOPED_PROVIDER
    SymbolDiscoveryQualification.UNSUPPORTED_ITEM ->
        KastNativeReadQualification.UNSUPPORTED_ITEM
    SymbolDiscoveryQualification.EXACT_DEFINITION_UNAVAILABLE ->
        KastNativeReadQualification.EXACT_DEFINITION_UNAVAILABLE
}
