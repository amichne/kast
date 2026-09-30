package io.github.amichne.kast.diagnostic.intellij

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.diagnostic.contract.DiagnosticFact
import io.github.amichne.kast.diagnostic.contract.DiagnosticLimitationReason
import io.github.amichne.kast.diagnostic.contract.DiagnosticSourceFile
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadPhase
import kotlinx.coroutines.CancellationException

/** One native analysis effect; only detached projections enter the request-local collector. */
internal fun collectDiagnosticAnalysis(
    file: DiagnosticSourceFile,
    collector: IntellijDiagnosticCollector,
    observation: IntellijReadObservation,
    analyze: () -> List<IntellijDiagnosticProjection>,
) {
    observation.phase(IntellijReadPhase.DIAGNOSTIC_ANALYSIS)
    try {
        val detached = mutableListOf<DiagnosticFact>()
        analyze().forEach { projection ->
            when (projection) {
                is IntellijDiagnosticProjection.Projected -> detached += projection.facts
                IntellijDiagnosticProjection.Rejected -> {
                    collector.recordLimitation(file, DiagnosticLimitationReason.UNSUPPORTED_DIAGNOSTIC)
                    return
                }
            }
        }
        if (detached.any { fact -> collector.accept(fact) == IntellijDiagnosticCollectionAdmission.REJECTED }) {
            collector.recordLimitation(file, DiagnosticLimitationReason.ANALYSIS_UNAVAILABLE)
            return
        }
        collector.recordAnalyzed(file)
    } catch (cancelled: ProcessCanceledException) {
        throw cancelled
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        collector.recordLimitation(file, DiagnosticLimitationReason.ANALYSIS_UNAVAILABLE)
    } catch (_: LinkageError) {
        collector.recordLimitation(file, DiagnosticLimitationReason.ANALYSIS_UNAVAILABLE)
    }
}
