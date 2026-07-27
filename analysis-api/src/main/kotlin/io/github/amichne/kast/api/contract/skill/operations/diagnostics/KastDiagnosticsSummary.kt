package io.github.amichne.kast.api.contract.skill

import io.github.amichne.kast.api.contract.Diagnostic
import io.github.amichne.kast.api.contract.DiagnosticSeverity
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.result.DiagnosticsResult
import io.github.amichne.kast.api.contract.result.RefreshResult
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
        require(errorCount >= errors.size) { "errorCount must include every returned error" }
        require(errors.all { it.severity == DiagnosticSeverity.ERROR }) {
            "errors must contain only error diagnostics"
        }
        require(clean == (semanticOutcome == SemanticAnalysisOutcome.COMPLETE && errorCount == 0)) {
            "clean requires complete semantic evidence without error diagnostics"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is KastDiagnosticsSummary) {
            return false
        }
        return clean == other.clean &&
            errorCount == other.errorCount &&
            warningCount == other.warningCount &&
            semanticOutcome == other.semanticOutcome &&
            requestedFileCount == other.requestedFileCount &&
            analyzedFileCount == other.analyzedFileCount &&
            skippedFileCount == other.skippedFileCount &&
            errors == other.errors
    }

    override fun hashCode(): Int {
        var result = clean.hashCode()
        result = 31 * result + errorCount
        result = 31 * result + warningCount
        result = 31 * result + semanticOutcome.hashCode()
        result = 31 * result + requestedFileCount
        result = 31 * result + analyzedFileCount
        result = 31 * result + skippedFileCount
        result = 31 * result + errors.hashCode()
        return result
    }

    companion object {
        fun from(result: DiagnosticsResult): KastDiagnosticsSummary =
            from(result, PositiveInt(Int.MAX_VALUE))

        fun from(result: RefreshResult): KastDiagnosticsSummary = KastDiagnosticsSummary(
            clean = result.semanticOutcome == SemanticAnalysisOutcome.COMPLETE,
            errorCount = 0,
            warningCount = 0,
            semanticOutcome = result.semanticOutcome,
            requestedFileCount = result.requestedFileCount,
            analyzedFileCount = result.analyzedFileCount,
            skippedFileCount = result.skippedFileCount,
        )

        fun from(
            result: DiagnosticsResult,
            maxReturnedErrors: PositiveInt,
        ): KastDiagnosticsSummary {
            val errors = result.diagnostics.filter { it.severity == DiagnosticSeverity.ERROR }
            return KastDiagnosticsSummary(
                clean = result.semanticOutcome == SemanticAnalysisOutcome.COMPLETE && errors.isEmpty(),
                errorCount = errors.size,
                warningCount = result.diagnostics.count { it.severity == DiagnosticSeverity.WARNING },
                semanticOutcome = result.semanticOutcome,
                requestedFileCount = result.requestedFileCount,
                analyzedFileCount = result.analyzedFileCount,
                skippedFileCount = result.skippedFileCount,
                errors = errors.take(maxReturnedErrors.value),
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
