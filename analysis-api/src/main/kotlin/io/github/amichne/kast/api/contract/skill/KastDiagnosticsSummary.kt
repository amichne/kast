package io.github.amichne.kast.api.contract.skill

import io.github.amichne.kast.api.contract.Diagnostic
import io.github.amichne.kast.api.contract.DiagnosticSeverity
import io.github.amichne.kast.api.contract.result.DiagnosticsResult
import io.github.amichne.kast.api.contract.result.SemanticAnalysisOutcome
import kotlinx.serialization.Serializable

@Serializable
class KastDiagnosticsSummary private constructor(
    val clean: Boolean,
    val errorCount: Int,
    val warningCount: Int,
    val semanticOutcome: SemanticAnalysisOutcome,
    val requestedFileCount: Int,
    val analyzedFileCount: Int,
    val skippedFileCount: Int,
    val errors: List<Diagnostic> = emptyList(),
) {
    init {
        require(errorCount >= 0) { "errorCount must not be negative" }
        require(warningCount >= 0) { "warningCount must not be negative" }
        require(requestedFileCount == analyzedFileCount + skippedFileCount) {
            "requestedFileCount must equal analyzedFileCount plus skippedFileCount"
        }
        require(errorCount == errors.size) { "errorCount must match errors" }
        require(errors.all { it.severity == DiagnosticSeverity.ERROR }) {
            "errors must contain only error diagnostics"
        }
        require(clean == (semanticOutcome == SemanticAnalysisOutcome.COMPLETE && errorCount == 0)) {
            "clean requires complete semantic evidence without error diagnostics"
        }
    }

    companion object {
        fun from(result: DiagnosticsResult): KastDiagnosticsSummary {
            val errors = result.diagnostics.filter { it.severity == DiagnosticSeverity.ERROR }
            return KastDiagnosticsSummary(
                clean = result.semanticOutcome == SemanticAnalysisOutcome.COMPLETE && errors.isEmpty(),
                errorCount = errors.size,
                warningCount = result.diagnostics.count { it.severity == DiagnosticSeverity.WARNING },
                semanticOutcome = result.semanticOutcome,
                requestedFileCount = result.requestedFileCount,
                analyzedFileCount = result.analyzedFileCount,
                skippedFileCount = result.skippedFileCount,
                errors = errors,
            )
        }

        fun completeWithoutFiles(): KastDiagnosticsSummary = KastDiagnosticsSummary(
            clean = true,
            errorCount = 0,
            warningCount = 0,
            semanticOutcome = SemanticAnalysisOutcome.COMPLETE,
            requestedFileCount = 0,
            analyzedFileCount = 0,
            skippedFileCount = 0,
        )
    }
}
